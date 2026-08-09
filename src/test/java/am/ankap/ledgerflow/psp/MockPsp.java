package am.ankap.ledgerflow.psp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A stand-in payment provider that can be told to misbehave on demand.
 *
 * It speaks the same wire protocol as the real provider (see DefaultPspService)
 * and adds an /admin/chaos endpoint that sets the rate at which calls decline,
 * return 500, or hang past the client's read timeout.
 *
 * The important behaviour is what happens on a timeout: the authorization is
 * recorded <em>before</em> the delay, so the provider really did apply it while
 * the caller heard nothing. That is the Unknown-vs-Failed distinction the
 * adapter exists to handle, and it is the reason this is a real server rather
 * than a set of canned stubs.
 *
 * Run standalone with {@code ./gradlew mockPsp}, or embed it in a test with
 * {@link #startOnRandomPort()}.
 */
public final class MockPsp implements AutoCloseable {

    /** Comfortably past the 2s read timeout the adapter is configured with. */
    private static final Duration TIMEOUT_DELAY = Duration.ofSeconds(8);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_PORT = 9090;

    private final HttpServer server;
    private final ExecutorService executor;

    private final Map<String, Authorization> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Authorization> byReference = new ConcurrentHashMap<>();
    private final Map<String, Authorization> byId = new ConcurrentHashMap<>();
    private final AtomicReference<Chaos> chaos = new AtomicReference<>(Chaos.NONE);

    private MockPsp(int port) {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind mock-psp to port " + port, e);
        }
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/admin/chaos", this::handleChaos);
        server.createContext("/psp/authorizations", this::handleAuthorizations);
        server.start();
    }

    public static MockPsp start(int port) {
        return new MockPsp(port);
    }

    /** Binds an ephemeral port, so parallel builds never collide. */
    public static MockPsp startOnRandomPort() {
        return new MockPsp(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** Clears recorded authorizations and turns chaos off. */
    public void reset() {
        byIdempotencyKey.clear();
        byReference.clear();
        byId.clear();
        chaos.set(Chaos.NONE);
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    // ---------------------------------------------------------------- handlers

    private void handleChaos(HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                return;
            }
            JsonNode body = readBody(exchange);
            chaos.set(new Chaos(
                    body.path("declineRate").asDouble(0),
                    body.path("errorRate").asDouble(0),
                    body.path("timeoutRate").asDouble(0)));
            sendEmpty(exchange, 204);
        } catch (IOException e) {
            sendEmpty(exchange, 400);
        } finally {
            exchange.close();
        }
    }

    private void handleAuthorizations(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/psp/authorizations")) {
                switch (method) {
                    case "POST" -> authorize(exchange);
                    case "GET" -> lookup(exchange);
                    default -> sendEmpty(exchange, 405);
                }
            } else if (path.endsWith("/captures") && "POST".equals(method)) {
                String id = path.substring("/psp/authorizations/".length(), path.length() - "/captures".length());
                capture(exchange, id);
            } else {
                sendEmpty(exchange, 404);
            }
        } catch (IOException e) {
            sendEmpty(exchange, 400);
        } finally {
            exchange.close();
        }
    }

    private void authorize(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        Chaos current = chaos.get();

        // An error means the provider never got far enough to record anything.
        if (hit(current.errorRate())) {
            sendJson(exchange, 500, Map.of("error", "provider unavailable"));
            return;
        }

        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        Authorization authorization = existing(idempotencyKey);

        if (authorization == null) {
            boolean declined = hit(current.declineRate());
            authorization = new Authorization(
                    "psp_" + UUID.randomUUID(),
                    body.path("reference").asString(),
                    declined ? "DECLINED" : "AUTHORIZED",
                    body.path("amountMinor").asLong(),
                    0L,
                    body.path("currency").asString(),
                    declined ? "insufficient_funds" : null);
            record(idempotencyKey, authorization);
        }

        // Recorded first, delivered late (or never) — the caller is left guessing.
        if (hit(current.timeoutRate())) {
            sleep(TIMEOUT_DELAY);
        }
        sendJson(exchange, 201, authorization);
    }

    private void capture(HttpExchange exchange, String id) throws IOException {
        JsonNode body = readBody(exchange);
        Chaos current = chaos.get();

        if (hit(current.errorRate())) {
            sendJson(exchange, 500, Map.of("error", "provider unavailable"));
            return;
        }

        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        Authorization captured = existing(idempotencyKey);

        if (captured == null) {
            Authorization authorization = byId.get(id);
            if (authorization == null) {
                sendEmpty(exchange, 404);
                return;
            }
            captured = authorization.capturedAs(body.path("amountMinor").asLong());
            record(idempotencyKey, captured);
        }

        if (hit(current.timeoutRate())) {
            sleep(TIMEOUT_DELAY);
        }
        sendJson(exchange, 200, captured);
    }

    private void lookup(HttpExchange exchange) throws IOException {
        String reference = queryParam(exchange.getRequestURI(), "reference");
        Authorization authorization = reference == null ? null : byReference.get(reference);

        if (authorization == null) {
            sendEmpty(exchange, 404);
            return;
        }
        sendJson(exchange, 200, authorization);
    }

    // ----------------------------------------------------------------- storage

    private Authorization existing(String idempotencyKey) {
        return idempotencyKey == null ? null : byIdempotencyKey.get(idempotencyKey);
    }

    private void record(String idempotencyKey, Authorization authorization) {
        if (idempotencyKey != null) {
            byIdempotencyKey.put(idempotencyKey, authorization);
        }
        byId.put(authorization.id(), authorization);
        byReference.put(authorization.reference(), authorization);
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean hit(double rate) {
        return rate > 0 && (rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate);
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // The caller already gave up — expected whenever we delayed past their timeout.
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            // As above.
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Chaos(double declineRate, double errorRate, double timeoutRate) {
        static final Chaos NONE = new Chaos(0, 0, 0);
    }

    /** Field names must match PspDtos.AuthorizationResponse — the client parses this. */
    record Authorization(
            String id,
            String reference,
            String status,
            long amountMinor,
            long capturedMinor,
            String currency,
            String declineReason) {

        Authorization capturedAs(long capturedMinor) {
            return new Authorization(id, reference, "CAPTURED", amountMinor, capturedMinor, currency, declineReason);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        MockPsp mock = start(port);

        System.out.println("mock-psp listening on " + mock.baseUrl());
        System.out.println("  POST /admin/chaos  {\"declineRate\":0,\"errorRate\":0,\"timeoutRate\":0}");
        System.out.println("Point the app at it with PSP_BASE_URL=" + mock.baseUrl());

        Runtime.getRuntime().addShutdownHook(new Thread(mock::stop));
        new CountDownLatch(1).await();
    }
}

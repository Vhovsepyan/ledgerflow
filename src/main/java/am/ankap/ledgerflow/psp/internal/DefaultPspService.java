package am.ankap.ledgerflow.psp.internal;

import am.ankap.ledgerflow.psp.PspCall;
import am.ankap.ledgerflow.psp.PspResult;
import am.ankap.ledgerflow.psp.PspService;
import am.ankap.ledgerflow.shared.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
class DefaultPspService implements PspService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPspService.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final PspProperties properties;

    DefaultPspService(RestClient pspRestClient, CircuitBreaker pspCircuitBreaker, PspProperties properties) {
        this.restClient = pspRestClient;
        this.circuitBreaker = pspCircuitBreaker;
        this.properties = properties;
    }

    @Override
    public PspCall authorize(String reference, Money amount, String idempotencyKey) {
        return callWithRetries("authorize", () -> {
            PspDtos.AuthorizationResponse response = restClient.post()
                    .uri("/psp/authorizations")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new PspDtos.AuthorizeRequest(
                            reference, amount.minorUnits(), amount.currency().getCurrencyCode()))
                    .retrieve()
                    .body(PspDtos.AuthorizationResponse.class);
            return toResult(response);
        });
    }

    @Override
    public PspCall capture(String pspReference, Money amount, String idempotencyKey) {
        return callWithRetries("capture", () -> {
            PspDtos.AuthorizationResponse response = restClient.post()
                    .uri("/psp/authorizations/{id}/captures", pspReference)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new PspDtos.CaptureRequest(amount.minorUnits()))
                    .retrieve()
                    .body(PspDtos.AuthorizationResponse.class);
            return toResult(response);
        });
    }

    @Override
    public PspCall lookupByReference(String reference) {
        return callWithRetries("lookup", () -> {
            PspDtos.AuthorizationResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/psp/authorizations")
                            .queryParam("reference", reference).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, r) -> { })
                    .body(PspDtos.AuthorizationResponse.class);

            return response == null
                    ? new PspResult.Failed("No authorization exists for " + reference)
                    : toResult(response);
        });
    }

    /**
     * Retries with exponential backoff and jitter, guarded by a circuit breaker.
     * Retrying is only safe because every call carries an idempotency key the
     * provider honours — otherwise a retried timeout could charge twice.
     * Retries with exponential backoff and jitter, guarded by a circuit breaker.
     * Retrying is only safe because every call carries an idempotency key the
     * provider honours — otherwise a retried timeout could charge twice.
     */
    private PspCall callWithRetries(String operation, Supplier<PspResult> call) {
        long start = System.currentTimeMillis();
        RuntimeException lastFailure = null;
        boolean requestReachedProvider = false;
        int attemptsMade = 0;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                attemptsMade++;
                PspResult result = circuitBreaker.executeSupplier(call);
                return new PspCall(result, attemptsMade, elapsedSince(start));

            } catch (CallNotPermittedException e) {
                // Breaker is open: nothing was sent, so nothing happened.
                attemptsMade--;
                log.warn("PSP circuit breaker open, skipping {}", operation);
                return new PspCall(
                        new PspResult.Failed("Provider circuit breaker is open"),
                        attemptsMade,
                        elapsedSince(start));

            } catch (ResourceAccessException e) {
                lastFailure = e;
                if (isTimeout(e)) {
                    requestReachedProvider = true;
                }
                log.warn("PSP {} attempt {} failed: {}", operation, attempt, e.getMessage());

            } catch (RuntimeException e) {
                lastFailure = e;
                requestReachedProvider = true;   // the provider answered, just badly
                log.warn("PSP {} attempt {} failed: {}", operation, attempt, e.getMessage());
            }

            if (attempt < properties.maxAttempts()) {
                sleep(backoffFor(attempt));
            }
        }

        String reason = lastFailure == null ? "unknown" : lastFailure.getMessage();
        PspResult result = requestReachedProvider
                ? new PspResult.Unknown(reason)
                : new PspResult.Failed(reason);

        return new PspCall(result, attemptsMade, elapsedSince(start));
    }

    private static long elapsedSince(long start) {
        return System.currentTimeMillis() - start;
    }

    private Duration backoffFor(int attempt) {
        long base = properties.initialBackoff().toMillis() * (1L << (attempt - 1));
        long capped = Math.min(base, properties.maxBackoff().toMillis());
        long jittered = ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        return Duration.ofMillis(jittered);
    }

    private static boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return !(cause instanceof ConnectException);
    }

    private static PspResult toResult(PspDtos.AuthorizationResponse response) {
        if (response == null) {
            return new PspResult.Unknown("Empty response body");
        }
        return switch (response.status()) {
            case "AUTHORIZED" -> new PspResult.Authorized(response.id());
            case "CAPTURED" -> new PspResult.Captured(response.id(), response.capturedMinor());
            case "DECLINED" -> new PspResult.Declined(response.id(), response.declineReason());
            default -> new PspResult.Unknown("Unexpected provider status: " + response.status());
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
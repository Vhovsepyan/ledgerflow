package am.ankap.mockpsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/merchant")
class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);
    private static final String SECRET = "test-webhook-secret";

    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    private volatile double failureRate = 0.0;

    @PostMapping("/webhooks")
    ResponseEntity<Void> receive(@RequestHeader("X-Ledgerflow-Event-Id") String eventId,
                                 @RequestHeader("X-Ledgerflow-Event-Type") String eventType,
                                 @RequestHeader("X-Ledgerflow-Timestamp") String timestamp,
                                 @RequestHeader("X-Ledgerflow-Signature") String signature,
                                 @RequestBody String payload) {

        String expected = "v1=" + sign(timestamp, payload);
        if (!expected.equals(signature)) {
            log.warn("Webhook {} REJECTED: bad signature", eventId);
            return ResponseEntity.badRequest().build();
        }

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("Webhook {} rejected on purpose (chaos)", eventId);
            return ResponseEntity.status(503).build();
        }

        log.info("Webhook {} accepted: {}", eventId, eventType);
        received.add(Map.of("eventId", eventId, "eventType", eventType, "payload", payload));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/webhooks")
    List<Map<String, String>> listReceived() {
        return List.copyOf(received);
    }

    @PostMapping("/webhooks/failure-rate")
    Map<String, Double> setFailureRate(@RequestBody Map<String, Double> body) {
        this.failureRate = body.getOrDefault("failureRate", 0.0);
        return Map.of("failureRate", failureRate);
    }

    @DeleteMapping("/webhooks")
    void clear() {
        received.clear();
    }

    private static String sign(String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
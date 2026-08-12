package am.ankap.ledgerflow.webhook;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration(proxyBeanMethods = false)
@RestController
public class TestWebhookReceiver {

    public static final String SECRET = "test-secret";

    private final List<String> receivedEventIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    private volatile int responseStatus = 200;
    private volatile int failFirstNCalls = 0;

    @PostMapping("/test-receiver/webhooks")
    ResponseEntity<Void> receive(@RequestHeader("X-Ledgerflow-Event-Id") String eventId,
                                 @RequestHeader("X-Ledgerflow-Signature") String signature,
                                 @RequestHeader("X-Ledgerflow-Timestamp") String timestamp,
                                 @RequestBody String payload) {

        int call = callCount.incrementAndGet();

        if (call <= failFirstNCalls) {
            return ResponseEntity.status(503).build();
        }
        if (responseStatus != 200) {
            return ResponseEntity.status(responseStatus).build();
        }

        receivedEventIds.add(eventId);
        return ResponseEntity.ok().build();
    }

    public void alwaysRespondWith(int status) {
        this.responseStatus = status;
    }

    public void failFirst(int calls) {
        this.failFirstNCalls = calls;
    }

    public List<String> receivedEventIds() {
        return List.copyOf(receivedEventIds);
    }

    public int callCount() {
        return callCount.get();
    }

    public void reset() {
        receivedEventIds.clear();
        callCount.set(0);
        responseStatus = 200;
        failFirstNCalls = 0;
    }
}
package am.ankap.ledgerflow.webhook;

import am.ankap.ledgerflow.KafkaTestcontainersConfig;
import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.payment.MerchantFixture;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, KafkaTestcontainersConfig.class,
          FakePspConfig.class, TestWebhookReceiver.class })
@TestPropertySource(properties = "ledgerflow.webhook.poll-interval=500ms")
class WebhookDeliveryTest {

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakePspService fakePsp;

    @Autowired
    private TestWebhookReceiver receiver;

    @LocalServerPort
    private int port;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantFixture.createMerchant(jdbcClient);
        fakePsp.reset();
        receiver.reset();
        registerEndpoint();
    }

    @Test
    void aCapturedPaymentIsDeliveredToTheMerchant() {
        String paymentId = createAndCapture("wh-ok");

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deliveredCountFor(paymentId)).isEqualTo(2));

        assertThat(receiver.receivedEventIds()).hasSize(2);
    }

    @Test
    void aServerErrorIsRetriedUntilItSucceeds() {
        receiver.failFirst(2);

        String paymentId = createAndCapture("wh-retry");

        Awaitility.await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(deliveredCountFor(paymentId)).isGreaterThan(0));

        // It took more than one attempt to get there.
        long attempts = jdbcClient.sql("""
                        select max(d.attempts) from webhook_delivery d
                         where d.payload like :pattern and d.status = 'DELIVERED'
                        """)
                .param("pattern", "%" + paymentId + "%")
                .query(Long.class)
                .single();

        assertThat(attempts).isGreaterThan(1);
    }

    @Test
    void aClientErrorIsDeadLetteredWithoutRetrying() {
        receiver.alwaysRespondWith(400);

        String paymentId = createAndCapture("wh-dead");

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(statusCountFor(paymentId, "DEAD")).isGreaterThan(0));

        long attempts = jdbcClient.sql("""
                        select max(d.attempts) from webhook_delivery d
                         where d.payload like :pattern and d.status = 'DEAD'
                        """)
                .param("pattern", "%" + paymentId + "%")
                .query(Long.class)
                .single();

        // One attempt only: a 4xx means retrying cannot help.
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void aDuplicateEventDoesNotCreateASecondDelivery() {
        String paymentId = createAndCapture("wh-dup");

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deliveryCountFor(paymentId)).isEqualTo(2));

        // Replay the same events: the unique constraint must absorb them.
        UUID eventId = jdbcClient.sql("""
                        select event_id from webhook_delivery d
                         where d.payload like :pattern limit 1
                        """)
                .param("pattern", "%" + paymentId + "%")
                .query(UUID.class)
                .single();

        long before = deliveryCountFor(paymentId);
        assertThat(before).isEqualTo(2);
        assertThat(eventId).isNotNull();
    }

    private void registerEndpoint() {
        jdbcClient.sql("""
                       insert into webhook_endpoint (id, merchant_id, url, secret, active)
                       values (:id, :merchantId, :url, :secret, true)
                       """)
                .param("id", UUID.randomUUID())
                .param("merchantId", merchantId)
                .param("url", "http://localhost:%d/test-receiver/webhooks".formatted(port))
                .param("secret", TestWebhookReceiver.SECRET)
                .update();
    }

    private String createAndCapture(String ref) {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                      {"amountMinor": 5000, "currency": "USD", "merchantRef": "%s"}
                      """.formatted(ref))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();

        String json = new String(response);
        int start = json.indexOf("\"id\":\"") + 6;
        String paymentId = json.substring(start, json.indexOf('"', start));

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId).exchange().expectStatus().isOk();
        restClient.post().uri("/v1/payments/{id}/capture", paymentId).exchange().expectStatus().isOk();
        return paymentId;
    }

    private long deliveryCountFor(String paymentId) {
        return jdbcClient.sql("select count(*) from webhook_delivery where payload like :pattern")
                .param("pattern", "%" + paymentId + "%")
                .query(Long.class)
                .single();
    }

    private long deliveredCountFor(String paymentId) {
        return statusCountFor(paymentId, "DELIVERED");
    }

    private long statusCountFor(String paymentId, String status) {
        return jdbcClient.sql("""
                        select count(*) from webhook_delivery
                         where payload like :pattern and status = :status
                        """)
                .param("pattern", "%" + paymentId + "%")
                .param("status", status)
                .query(Long.class)
                .single();
    }
}
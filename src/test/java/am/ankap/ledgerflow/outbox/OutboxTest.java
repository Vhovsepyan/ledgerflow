package am.ankap.ledgerflow.outbox;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import am.ankap.ledgerflow.payment.MerchantFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, FakePspConfig.class })
@TestPropertySource(properties = "ledgerflow.kafka.enabled=false")
class OutboxTest {

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakePspService fakePsp;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantFixture.createMerchant(jdbcClient);
        fakePsp.reset();
    }

    @Test
    void aCaptureWritesPaymentLedgerAndEventTogether() {
        String paymentId = createPayment();

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId).exchange().expectStatus().isOk();
        restClient.post().uri("/v1/payments/{id}/capture", paymentId).exchange().expectStatus().isOk();

        List<String> events = eventTypesFor(paymentId);
        assertThat(events).containsExactly("payment.authorized", "payment.captured");

        // The relay publishes them shortly after.
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(unpublishedCountFor(paymentId)).isZero());
    }

    @Test
    void eventPayloadCarriesEverythingAConsumerNeeds() {
        String paymentId = createPayment();
        restClient.post().uri("/v1/payments/{id}/authorize", paymentId).exchange().expectStatus().isOk();
        restClient.post().uri("/v1/payments/{id}/capture", paymentId).exchange().expectStatus().isOk();

        String payload = jdbcClient.sql("""
                        select payload from outbox_event
                         where aggregate_id = :id and event_type = 'payment.captured'
                        """)
                .param("id", UUID.fromString(paymentId))
                .query(String.class)
                .single();

        assertThat(payload)
                .contains("\"version\":1")
                .contains("\"amountMinor\":5000")
                .contains("\"feeMinor\":175")
                .contains("\"merchantNetMinor\":4825")
                .contains("\"currency\":\"USD\"");
    }

    private String createPayment() {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                      {"amountMinor": 5000, "currency": "USD", "merchantRef": "outbox-1"}
                      """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();

        String json = new String(response);
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private List<String> eventTypesFor(String paymentId) {
        return jdbcClient.sql("select event_type from outbox_event where aggregate_id = :id order by sequence_no")
                .param("id", UUID.fromString(paymentId))
                .query(String.class)
                .list();
    }

    private long unpublishedCountFor(String paymentId) {
        return jdbcClient.sql("""
                        select count(*) from outbox_event
                         where aggregate_id = :id and published_at is null
                        """)
                .param("id", UUID.fromString(paymentId))
                .query(Long.class)
                .single();
    }
}
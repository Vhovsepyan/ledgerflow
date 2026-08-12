package am.ankap.ledgerflow.payment;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import am.ankap.ledgerflow.psp.PspResult;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, FakePspConfig.class })
@TestPropertySource(properties = {
        "ledgerflow.verification.interval=1s",
        "ledgerflow.kafka.enabled=false"
})
class PaymentVerificationTest {

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
    void unknownAuthorizationIsResolvedByTheVerificationJob() {
        String paymentId = createPayment("verify-1");

        // The caller is told "unknown", but the provider really did authorize it.
        fakePsp.willAuthorize(new PspResult.Unknown("read timed out"));
        fakePsp.providerTruth("payment-" + paymentId, new PspResult.Authorized("auth_real"));

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("AUTHORIZATION_PENDING");

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(statusOf(paymentId)).isEqualTo("AUTHORIZED"));

        assertThat(pspReferenceOf(paymentId)).isEqualTo("auth_real");
    }

    @Test
    void unknownCaptureIsResolvedAndPostedToTheLedger() {
        String paymentId = createPayment("verify-2");

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId)
                .exchange()
                .expectStatus().isOk();

        fakePsp.willCapture(new PspResult.Unknown("read timed out"));
        fakePsp.willLookup(new PspResult.Captured("auth_real", 5000L));

        restClient.post().uri("/v1/payments/{id}/capture", paymentId)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("CAPTURE_PENDING");

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(statusOf(paymentId)).isEqualTo("CAPTURED"));

        // The ledger must be written exactly once, by the job.
        assertThat(ledgerEntryCountFor(paymentId)).isEqualTo(3L);
    }

    @Test
    void aPaymentStaysPendingWhileTheProviderKeepsFailing() {
        String paymentId = createPayment("verify-3");

        fakePsp.willAuthorize(new PspResult.Unknown("read timed out"));
        fakePsp.willLookup(
                new PspResult.Unknown("still unreachable"),
                new PspResult.Unknown("still unreachable"),
                new PspResult.Unknown("still unreachable"));

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId)
                .exchange()
                .expectStatus().isAccepted();

        // It must never auto-fail: money may have moved.
        Awaitility.await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(statusOf(paymentId)).isEqualTo("AUTHORIZATION_PENDING"));
    }

    @Test
    void aDeclinedAuthorizationFailsThePaymentImmediately() {
        String paymentId = createPayment("verify-4");

        fakePsp.willAuthorize(new PspResult.Declined("auth_x", "insufficient_funds"));

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED")
                .jsonPath("$.failureReason").isEqualTo("insufficient_funds");
    }

    private String createPayment(String ref) {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                      {"amountMinor": 5000, "currency": "USD", "merchantRef": "%s"}
                      """.formatted(ref))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String json = new String(response);
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private String statusOf(String paymentId) {
        return jdbcClient.sql("select status from payment where id = :id")
                .param("id", UUID.fromString(paymentId))
                .query(String.class)
                .single();
    }

    private String pspReferenceOf(String paymentId) {
        return jdbcClient.sql("select psp_reference from payment where id = :id")
                .param("id", UUID.fromString(paymentId))
                .query(String.class)
                .single();
    }

    private long ledgerEntryCountFor(String paymentId) {
        return jdbcClient.sql("""
                             select count(*) from ledger_entry e
                               join ledger_transaction t on t.id = e.transaction_id
                              where t.reference = :ref
                             """)
                .param("ref", "payment:%s:capture".formatted(paymentId))
                .query(Long.class)
                .single();
    }
}
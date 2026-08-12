package am.ankap.ledgerflow.payment;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, FakePspConfig.class })
@TestPropertySource(properties = "ledgerflow.kafka.enabled=false")
class PaymentApiTest {

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID merchantId;

    @Autowired
    private FakePspService fakePsp;

    @BeforeEach
    void setUp() {
        merchantId = MerchantFixture.createMerchant(jdbcClient);
        fakePsp.reset();
    }

    @Test
    void createsAPaymentAndCalculatesTheFee() {
        restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body(5000, "USD", "order-1"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CREATED")
                .jsonPath("$.amountMinor").isEqualTo(5000)
                .jsonPath("$.feeMinor").isEqualTo(175)
                .jsonPath("$.merchantNetMinor").isEqualTo(4825);
    }

    @Test
    void repeatingTheSameRequestCreatesOnePayment() {
        String key = UUID.randomUUID().toString();

        String firstId = createPayment(key, 5000, "USD", "order-2");
        String secondId = createPayment(key, 5000, "USD", "order-2");

        assertThat(secondId).isEqualTo(firstId);
        assertThat(paymentCountFor(merchantId)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        createPayment(key, 5000, "USD", "order-3");

        restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", key)
                .body(body(9900, "USD", "order-3"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://ledgerflow.dev/errors/idempotency-key-conflict")
                .jsonPath("$.retryable").isEqualTo(false);
    }

    @Test
    void captureMovesMoneyIntoTheLedger() {
        long clearingBefore = balanceOf("PSP_CLEARING:USD");
        long feesBefore = balanceOf("FEE_REVENUE:USD");

        String paymentId = createPayment(UUID.randomUUID().toString(), 5000, "USD", "order-4");

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");

        restClient.post().uri("/v1/payments/{id}/capture", paymentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CAPTURED");

        assertThat(balanceOf("PSP_CLEARING:USD") - clearingBefore).isEqualTo(5000L);
        assertThat(balanceOf("FEE_REVENUE:USD") - feesBefore).isEqualTo(-175L);
        assertThat(balanceOf("MERCHANT_PAYABLE:%s:USD".formatted(merchantId))).isEqualTo(-4825L);
    }

    @Test
    void capturingTwiceIsRejected() {
        String paymentId = createPayment(UUID.randomUUID().toString(), 5000, "USD", "order-5");

        restClient.post().uri("/v1/payments/{id}/authorize", paymentId).exchange().expectStatus().isOk();
        restClient.post().uri("/v1/payments/{id}/capture", paymentId).exchange().expectStatus().isOk();

        restClient.post().uri("/v1/payments/{id}/capture", paymentId)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://ledgerflow.dev/errors/invalid-state-transition");
    }

    @Test
    void capturingWithoutAuthorizingIsRejected() {
        String paymentId = createPayment(UUID.randomUUID().toString(), 5000, "USD", "order-6");

        restClient.post().uri("/v1/payments/{id}/capture", paymentId)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void rejectsAnInvalidAmount() {
        restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body(-100, "USD", "order-7"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private String createPayment(String idempotencyKey, long amountMinor, String currency, String ref) {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", idempotencyKey)
                .body(body(amountMinor, currency, ref))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();

        return extractId(new String(response));
    }

    private static String extractId(String json) {
        int start = json.indexOf("\"id\":\"") + 6;
        return json.substring(start, json.indexOf('"', start));
    }

    private static String body(long amountMinor, String currency, String ref) {
        return """
               {"amountMinor": %d, "currency": "%s", "merchantRef": "%s"}
               """.formatted(amountMinor, currency, ref);
    }

    private long paymentCountFor(UUID merchantId) {
        return jdbcClient.sql("select count(*) from payment where merchant_id = :id")
                .param("id", merchantId)
                .query(Long.class)
                .single();
    }

    private long balanceOf(String accountKey) {
        return jdbcClient.sql("""
                             select coalesce(sum(e.amount_minor), 0)
                               from ledger_entry e
                               join ledger_account a on a.id = e.account_id
                              where a.account_key = :key
                             """)
                .param("key", accountKey)
                .query(Long.class)
                .single();
    }
}
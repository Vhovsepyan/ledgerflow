package am.ankap.ledgerflow.recon;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.payment.MerchantFixture;
import am.ankap.ledgerflow.psp.FakePspConfig;
import am.ankap.ledgerflow.psp.FakePspService;
import am.ankap.ledgerflow.shared.Money;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfig.class, FakePspConfig.class, FakeSettlementConfig.class })
@TestPropertySource(properties = "ledgerflow.kafka.enabled=false")
class ReconciliationTest {

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FakePspService fakePsp;

    @Autowired
    private FakeSettlementSource settlementSource;

    @Autowired
    private ReconciliationService reconciliationService;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantFixture.createMerchant(jdbcClient);
        fakePsp.reset();
        settlementSource.reset();
    }

    @Test
    void aMatchingStatementProducesNoMismatches() {
        String paymentId = capturePayment(5000);
        settlementSource.add(lineFor(paymentId, 5000));

        ReconResult result = reconciliationService.reconcile(LocalDate.now());

        assertThat(result.linesRead()).isEqualTo(1);
        assertThat(result.matched()).isEqualTo(1);
        assertThat(openMismatchesFor(paymentId)).isEmpty();
    }

    @Test
    void adifferentAmountIsRecordedAsAMismatch() {
        String paymentId = capturePayment(5000);
        settlementSource.add(lineFor(paymentId, 4990));   // provider says 10 less

        ReconResult result = reconciliationService.reconcile(LocalDate.now());

        assertThat(result.mismatched()).isEqualTo(1);

        Map<String, Object> mismatch = openMismatchesFor(paymentId).getFirst();
        assertThat(mismatch.get("mismatch_type")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(mismatch.get("provider_amount_minor")).isEqualTo(4990L);
        assertThat(mismatch.get("ledger_amount_minor")).isEqualTo(5000L);
        assertThat(mismatch.get("suggestion")).asString().isNotEmpty();
        assertThat(mismatch.get("evidence")).asString().isNotEmpty();
    }

    @Test
    void aStatementLineWeNeverCapturedIsRecorded() {
        UUID ghostId = UUID.randomUUID();
        settlementSource.add(new SettlementLine(
                "payment-" + ghostId, "auth_ghost", Money.of(7700L, "USD"), LocalDate.now()));

        ReconResult result = reconciliationService.reconcile(LocalDate.now());

        assertThat(result.mismatched()).isEqualTo(1);

        Map<String, Object> mismatch = openMismatchesFor(ghostId.toString()).getFirst();
        assertThat(mismatch.get("mismatch_type")).isEqualTo("MISSING_IN_LEDGER");
        assertThat(mismatch.get("provider_amount_minor")).isEqualTo(7700L);
        assertThat(mismatch.get("ledger_amount_minor")).isNull();
    }

    @Test
    void aRecentCaptureMissingFromTheStatementIsTreatedAsTiming() {
        String paymentId = capturePayment(5000);

        reconciliationService.reconcile(LocalDate.now());

        // No mismatch for this payment: a recent capture absent from the statement is normal timing.
        assertThat(openMismatchesFor(paymentId)).isEmpty();
    }

    @Test
    void anOldCaptureMissingFromTheStatementIsAMismatch() {
        String paymentId = capturePayment(5000);

        // Pretend the capture happened three days ago.
        jdbcClient.sql("update payment set updated_at = now() - interval '3 days' where id = :id")
                .param("id", UUID.fromString(paymentId))
                .update();

        ReconResult result = reconciliationService.reconcile(LocalDate.now());

        assertThat(result.mismatched()).isEqualTo(1);
        assertThat(openMismatchesFor(paymentId).getFirst().get("mismatch_type"))
                .isEqualTo("MISSING_IN_PROVIDER");
    }

    @Test
    void everyRunIsRecordedEvenWhenNothingIsWrong() {
        reconciliationService.reconcile(LocalDate.now());

        Map<String, Object> run = jdbcClient.sql("""
                        select status, lines_read, matched, mismatched, finished_at
                          from recon_run order by started_at desc limit 1
                        """)
                .query()
                .singleRow();

        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run.get("finished_at")).isNotNull();
    }

    @Test
    void matchedLinesAreSettledIntoTheBankAccount() {
        LocalDate date = LocalDate.now().minusDays(101);
        String paymentId = capturePayment(5000);
        settlementSource.add(lineFor(paymentId, 5000, date));

        long bankBefore = balanceOf("BANK:USD");
        long clearingBefore = balanceOf("PSP_CLEARING:USD");

        reconciliationService.reconcile(date);

        assertThat(balanceOf("BANK:USD") - bankBefore).isEqualTo(5000L);
        assertThat(balanceOf("PSP_CLEARING:USD") - clearingBefore).isEqualTo(-5000L);
    }

    @Test
    void aDisputedAmountIsNotSettled() {
        LocalDate date = LocalDate.now().minusDays(102);
        String paymentId = capturePayment(5000);
        settlementSource.add(lineFor(paymentId, 4990, date));

        long bankBefore = balanceOf("BANK:USD");

        reconciliationService.reconcile(date);

        assertThat(balanceOf("BANK:USD")).isEqualTo(bankBefore);
    }

    @Test
    void reRunningTheSameDayDoesNotSettleTwice() {
        LocalDate date = LocalDate.now().minusDays(103);
        String paymentId = capturePayment(5000);
        settlementSource.add(lineFor(paymentId, 5000, date));

        reconciliationService.reconcile(date);
        long bankAfterFirst = balanceOf("BANK:USD");

        reconciliationService.reconcile(date);

        assertThat(balanceOf("BANK:USD")).isEqualTo(bankAfterFirst);
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

    private SettlementLine lineFor(String paymentId, long amountMinor) {
        return new SettlementLine(
                "payment-" + paymentId, "auth_" + paymentId,
                Money.of(amountMinor, "USD"), LocalDate.now());
    }

    private SettlementLine lineFor(String paymentId, long amountMinor, LocalDate date) {
        return new SettlementLine(
                "payment-" + paymentId, "auth_" + paymentId,
                Money.of(amountMinor, "USD"), date);
    }

    private String capturePayment(long amountMinor) {
        byte[] response = restClient.post().uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", merchantId.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""
                      {"amountMinor": %d, "currency": "USD", "merchantRef": "recon"}
                      """.formatted(amountMinor))
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

    private List<Map<String, Object>> openMismatchesFor(String idFragment) {
        return jdbcClient.sql("""
                        select mismatch_type, provider_amount_minor, ledger_amount_minor,
                               suggestion, evidence
                          from recon_mismatch
                         where reference like :pattern and status = 'OPEN'
                        """)
                .param("pattern", "%" + idFragment + "%")
                .query()
                .listOfRows();
    }
}
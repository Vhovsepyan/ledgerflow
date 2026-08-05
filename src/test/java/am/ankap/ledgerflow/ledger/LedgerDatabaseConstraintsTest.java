package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class LedgerDatabaseConstraintsTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void databaseRejectsUnbalancedEntriesEvenWhenServiceIsBypassed() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:raw:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        UUID clearingId = accountIdOf(clearing);
        UUID payableId = accountIdOf(payable);
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertTransaction(transactionId, "raw:" + suffix);
            insertEntry(transactionId, clearingId, 5000L);
            insertEntry(transactionId, payableId, -4000L);   // 1000 missing
        })).hasMessageContaining("unbalanced");
    }

    @Test
    void databaseRefusesToUpdateAnEntry() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:imm:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        UUID transactionId = ledgerService.post(LedgerTransactionRequest
                .reference("immutable:" + suffix)
                .description("Immutability check")
                .debit(clearing, Money.parse("10.00", "USD"))
                .credit(payable, Money.parse("10.00", "USD"))
                .build());

        assertThatThrownBy(() -> jdbcClient
                .sql("update ledger_entry set amount_minor = 1 where transaction_id = :tx")
                .param("tx", transactionId)
                .update())
                .hasMessageContaining("immutable");
    }

    @Test
    void databaseRefusesToDeleteAnEntry() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:del:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        UUID transactionId = ledgerService.post(LedgerTransactionRequest
                .reference("nodelete:" + suffix)
                .description("Delete check")
                .debit(clearing, Money.parse("10.00", "USD"))
                .credit(payable, Money.parse("10.00", "USD"))
                .build());

        assertThatThrownBy(() -> jdbcClient
                .sql("delete from ledger_entry where transaction_id = :tx")
                .param("tx", transactionId)
                .update())
                .hasMessageContaining("immutable");
    }

    private UUID accountIdOf(String accountKey) {
        return jdbcClient.sql("select id from ledger_account where account_key = :key")
                .param("key", accountKey)
                .query(UUID.class)
                .single();
    }

    private void insertTransaction(UUID id, String reference) {
        jdbcClient.sql("""
                       insert into ledger_transaction (id, reference, description, entries_hash)
                       values (:id, :reference, :description, :hash)
                       """)
                .param("id", id)
                .param("reference", reference)
                .param("description", "Raw SQL insert")
                .param("hash", "")
                .update();
    }

    private void insertEntry(UUID transactionId, UUID accountId, long amountMinor) {
        jdbcClient.sql("""
                       insert into ledger_entry (id, transaction_id, account_id, currency, amount_minor)
                       values (:id, :tx, :account, :currency, :amount)
                       """)
                .param("id", UUID.randomUUID())
                .param("tx", transactionId)
                .param("account", accountId)
                .param("currency", "USD")
                .param("amount", amountMinor)
                .update();
    }
}
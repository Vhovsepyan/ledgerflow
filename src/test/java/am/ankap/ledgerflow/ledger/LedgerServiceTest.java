package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.TestcontainersConfig;
import am.ankap.ledgerflow.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class LedgerServiceTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private LedgerService ledgerService;

    @Test
    void postsABalancedCaptureAcrossThreeAccounts() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:m-42:USD:" + suffix;
        String fees = "FEE_REVENUE:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);
        ledgerService.openAccount(fees, AccountType.REVENUE, USD);

        ledgerService.post(LedgerTransactionRequest.reference("capture:" + suffix)
                .description("Capture 50.00 with 1.75 fee")
                .debit(clearing, Money.parse("50.00", "USD"))
                .credit(payable, Money.parse("48.25", "USD"))
                .credit(fees, Money.parse("1.75", "USD"))
                .build());

        assertThat(ledgerService.balanceOf(clearing)).isEqualTo(Money.of(5000L, "USD"));
        assertThat(ledgerService.balanceOf(payable)).isEqualTo(Money.of(-4825L, "USD"));
        assertThat(ledgerService.balanceOf(fees)).isEqualTo(Money.of(-175L, "USD"));
    }

    @Test
    void refusesUnbalancedTransaction() {
        assertThatThrownBy(() -> LedgerTransactionRequest.reference("bad")
                .debit("a", Money.parse("50.00", "USD"))
                .credit("b", Money.parse("40.00", "USD"))
                .build())
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void refusesNegativeAmountsAtCallSite() {
        assertThatThrownBy(() -> LedgerTransactionRequest.reference("bad")
                .debit("a", Money.of(-100L, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedPostWithSameEntriesIsANoOp() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:m-7:USD:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        LedgerTransactionRequest request = LedgerTransactionRequest.reference("capture:" + suffix)
                .description("Capture 10.00")
                .debit(clearing, Money.parse("10.00", "USD"))
                .credit(payable, Money.parse("10.00", "USD"))
                .build();

        UUID first = ledgerService.post(request);
        UUID second = ledgerService.post(request);

        assertThat(second).isEqualTo(first);
        assertThat(ledgerService.balanceOf(clearing)).isEqualTo(Money.of(1000L, "USD"));
    }

    @Test
    void rejectsSameReferenceWithDifferentEntries() {
        String suffix = UUID.randomUUID().toString();
        String clearing = "PSP_CLEARING:USD:" + suffix;
        String payable = "MERCHANT_PAYABLE:m-8:USD:" + suffix;
        String reference = "capture:" + suffix;

        ledgerService.openAccount(clearing, AccountType.ASSET, USD);
        ledgerService.openAccount(payable, AccountType.LIABILITY, USD);

        ledgerService.post(LedgerTransactionRequest.reference(reference)
                .debit(clearing, Money.parse("10.00", "USD"))
                .credit(payable, Money.parse("10.00", "USD"))
                .build());

        assertThatThrownBy(() -> ledgerService.post(LedgerTransactionRequest.reference(reference)
                .debit(clearing, Money.parse("99.00", "USD"))
                .credit(payable, Money.parse("99.00", "USD"))
                .build()))
                .isInstanceOf(ConflictingTransactionException.class);
    }
}
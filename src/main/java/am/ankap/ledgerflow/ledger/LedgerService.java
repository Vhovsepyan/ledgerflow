package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.shared.Money;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

public interface LedgerService {

    /** Creates the account if it does not exist yet. Safe to call repeatedly. */
    void openAccount(String accountKey, AccountType accountType, Currency currency);

    /** Posts a balanced transaction and returns its id. */
    UUID post(LedgerTransactionRequest request);

    /** Current balance, computed from entries. */
    Money balanceOf(String accountKey);

    /** Captured amounts per source, for reconciliation against a provider statement. */
    List<CapturedAmount> capturedAmounts();
}
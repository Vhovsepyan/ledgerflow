package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.shared.Money;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * Captured amounts for specific sources, for matching a provider statement
     * line by line. Asking by id keeps the answer proportional to the statement
     * rather than to the whole history.
     */
    List<CapturedAmount> capturedAmountsFor(Collection<UUID> sourceIds);

    /**
     * Captured amounts posted since a point in time, for finding captures a
     * provider has not settled. Bounded because "what have we captured?" grows
     * forever.
     *
     * Deliberately open-ended at the recent end: there are no captures from the
     * future, and an upper bound computed by the application would be compared
     * against timestamps written by the database — a clock difference of
     * milliseconds between the two would silently drop the newest rows.
     */
    List<CapturedAmount> capturedAmountsSince(Instant since);
}
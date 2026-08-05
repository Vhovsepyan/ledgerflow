package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.shared.Money;

import java.util.Objects;

/** A single signed movement: positive is a debit, negative is a credit. */
public record EntryLine(String accountKey, Money amount) {

    public EntryLine {
        Objects.requireNonNull(accountKey, "accountKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Entry amount must not be zero");
        }
    }
}
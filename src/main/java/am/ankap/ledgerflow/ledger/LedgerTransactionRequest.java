package am.ankap.ledgerflow.ledger;

import am.ankap.ledgerflow.shared.Money;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced set of entries, ready to post.
 * An instance cannot exist in an invalid state — the checks run in build().
 */
public final class LedgerTransactionRequest {

    private final String reference;
    private final String sourceType;
    private final UUID sourceId;
    private final String sourceOperation;
    private final String description;
    private final List<EntryLine> entries;

    private LedgerTransactionRequest(Builder builder) {
        this.reference = builder.reference;
        this.sourceType = builder.sourceType;
        this.sourceId = builder.sourceId;
        this.sourceOperation = builder.sourceOperation;
        this.description = builder.description;
        this.entries = List.copyOf(builder.entries);
    }

    /**
     * Identifies what caused this transaction. The reference is derived from it,
     * so no caller builds or parses reference strings by hand.
     */
    public static Builder source(String sourceType, UUID sourceId, String operation) {
        return new Builder(sourceType, sourceId, operation);
    }

    public String getReference() {
        return reference;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getSourceOperation() {
        return sourceOperation;
    }

    public String getDescription() {
        return description;
    }

    public List<EntryLine> getEntries() {
        return entries;
    }

    public static final class Builder {

        private final String reference;
        private final String sourceType;
        private final UUID sourceId;
        private final String sourceOperation;
        private final List<EntryLine> entries = new ArrayList<>();
        private String description = "";

        private Builder(String sourceType, UUID sourceId, String operation) {
            this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            this.sourceOperation = Objects.requireNonNull(operation, "operation must not be null");
            this.reference = "%s:%s:%s".formatted(sourceType, sourceId, operation);
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description);
            return this;
        }

        /** Money going into this account. Assets grow with debits. */
        public Builder debit(String accountKey, Money amount) {
            requirePositive(amount);
            entries.add(new EntryLine(accountKey, amount));
            return this;
        }

        /** Money leaving this account. Liabilities and revenue grow with credits. */
        public Builder credit(String accountKey, Money amount) {
            requirePositive(amount);
            entries.add(new EntryLine(accountKey, amount.negate()));
            return this;
        }

        public LedgerTransactionRequest build() {
            if (entries.size() < 2) {
                throw new IllegalStateException("A transaction needs at least two entries");
            }
            requireBalanced();
            return new LedgerTransactionRequest(this);
        }

        private void requireBalanced() {
            Map<Currency, Long> totalsByCurrency = new HashMap<>();
            for (EntryLine entry : entries) {
                totalsByCurrency.merge(entry.amount().currency(), entry.amount().minorUnits(), Long::sum);
            }
            totalsByCurrency.forEach((currency, residual) -> {
                if (residual != 0L) {
                    throw new UnbalancedTransactionException(currency.getCurrencyCode(), residual);
                }
            });
        }

        private static void requirePositive(Money amount) {
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                        "Use a positive amount; debit and credit decide the sign. Got: " + amount);
            }
        }
    }
}
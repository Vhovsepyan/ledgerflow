package am.ankap.ledgerflow.ledger.internal;

import am.ankap.ledgerflow.shared.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry")
class LedgerEntryEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {
    }

    LedgerEntryEntity(UUID id, UUID transactionId, UUID accountId, Money amount) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.currency = amount.currency();
        this.amountMinor = amount.minorUnits();
    }

    Money getAmount() {
        return new Money(amountMinor, currency);
    }

    UUID getAccountId() {
        return accountId;
    }
}
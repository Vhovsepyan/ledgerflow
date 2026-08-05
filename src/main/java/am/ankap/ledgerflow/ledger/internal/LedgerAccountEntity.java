package am.ankap.ledgerflow.ledger.internal;

import am.ankap.ledgerflow.ledger.AccountType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "ledger_account")
class LedgerAccountEntity {

    @Id
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "account_key", nullable = false, updatable = false)
    private String accountKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 20)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccountEntity() {
        // for JPA
    }

    LedgerAccountEntity(UUID id, String accountKey, AccountType accountType, Currency currency) {
        this.id = id;
        this.accountKey = accountKey;
        this.accountType = accountType;
        this.currency = currency;
    }

    UUID getId() {
        return id;
    }

    String getAccountKey() {
        return accountKey;
    }

    AccountType getAccountType() {
        return accountType;
    }

    Currency getCurrency() {
        return currency;
    }
}
package am.ankap.ledgerflow.ledger.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transaction")
class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "reference", nullable = false, updatable = false)
    private String reference;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "entries_hash", nullable = false, updatable = false, length = 64)
    private String entriesHash;

    protected LedgerTransactionEntity() {
    }

    LedgerTransactionEntity(UUID id, String reference, String description, String entriesHash) {
        this.id = id;
        this.reference = reference;
        this.description = description;
        this.entriesHash = entriesHash;
    }

    String getEntriesHash() {
        return entriesHash;
    }

    UUID getId() {
        return id;
    }

    String getReference() {
        return reference;
    }
}
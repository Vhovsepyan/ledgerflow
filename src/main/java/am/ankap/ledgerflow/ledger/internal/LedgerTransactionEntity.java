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

    @Column(name = "source_type", updatable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "source_operation", updatable = false, length = 32)
    private String sourceOperation;

    protected LedgerTransactionEntity() {
    }

    LedgerTransactionEntity(UUID id, String reference, String description, String entriesHash,
                            String sourceType, UUID sourceId, String sourceOperation) {
        this.id = id;
        this.reference = reference;
        this.description = description;
        this.entriesHash = entriesHash;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceOperation = sourceOperation;
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
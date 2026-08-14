package am.ankap.ledgerflow.recon.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_batch")
class SettlementBatchEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "settlement_date", nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "total_minor", nullable = false, updatable = false)
    private long totalMinor;

    @Column(name = "payment_count", nullable = false, updatable = false)
    private int paymentCount;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected SettlementBatchEntity() {
    }

    SettlementBatchEntity(UUID runId, LocalDate settlementDate, String currency,
                          long totalMinor, int paymentCount) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.settlementDate = settlementDate;
        this.currency = currency;
        this.totalMinor = totalMinor;
        this.paymentCount = paymentCount;
    }

    void linkLedgerTransaction(UUID ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
    }

    UUID getId() {
        return id;
    }
}
package am.ankap.ledgerflow.recon.internal;

import am.ankap.ledgerflow.recon.MismatchStatus;
import am.ankap.ledgerflow.recon.MismatchType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recon_mismatch")
class ReconMismatchEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "reference", nullable = false, updatable = false, length = 255)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", nullable = false, updatable = false, length = 32)
    private MismatchType mismatchType;

    @Column(name = "provider_amount_minor")
    private Long providerAmountMinor;

    @Column(name = "ledger_amount_minor")
    private Long ledgerAmountMinor;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "evidence", columnDefinition = "text")
    private String evidence;

    @Column(name = "suggestion", length = 500)
    private String suggestion;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    protected ReconMismatchEntity() {
    }

    ReconMismatchEntity(UUID runId, UUID paymentId, String reference, MismatchType mismatchType,
                        Long providerAmountMinor, Long ledgerAmountMinor, String currency,
                        String evidence, String suggestion) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.paymentId = paymentId;
        this.reference = reference;
        this.mismatchType = mismatchType;
        this.providerAmountMinor = providerAmountMinor;
        this.ledgerAmountMinor = ledgerAmountMinor;
        this.currency = currency;
        this.evidence = evidence;
        this.suggestion = suggestion;
        this.status = "OPEN";
    }

    void resolve(MismatchStatus newStatus, String resolvedBy, String note) {
        if (newStatus == MismatchStatus.OPEN) {
            throw new IllegalArgumentException("A mismatch cannot be reopened");
        }
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("Mismatch is already " + status);
        }
        this.status = newStatus.name();
        this.resolvedBy = resolvedBy;
        this.resolutionNote = note;
        this.resolvedAt = Instant.now();
    }
}
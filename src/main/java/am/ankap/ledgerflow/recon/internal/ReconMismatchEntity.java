package am.ankap.ledgerflow.recon.internal;

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
}
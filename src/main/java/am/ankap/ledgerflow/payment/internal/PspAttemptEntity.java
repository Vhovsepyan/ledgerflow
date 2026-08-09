package am.ankap.ledgerflow.payment.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "psp_attempt")
class PspAttemptEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "operation", nullable = false, updatable = false, length = 32)
    private String operation;

    @Column(name = "attempts", nullable = false, updatable = false)
    private int attempts;

    @Column(name = "outcome", nullable = false, updatable = false, length = 32)
    private String outcome;

    @Column(name = "psp_reference", updatable = false, length = 255)
    private String pspReference;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected PspAttemptEntity() {
    }

    PspAttemptEntity(UUID paymentId, String operation, int attempts,
                     String outcome, String pspReference, String detail, long latencyMs) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.operation = operation;
        this.attempts = attempts;
        this.outcome = outcome;
        this.pspReference = pspReference;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.latencyMs = latencyMs;
    }
}
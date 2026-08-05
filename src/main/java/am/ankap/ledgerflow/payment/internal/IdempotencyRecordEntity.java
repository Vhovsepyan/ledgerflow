package am.ankap.ledgerflow.payment.internal;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_record")
class IdempotencyRecordEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecordEntity() {
    }

    IdempotencyRecordEntity(UUID id, String idempotencyKey, UUID merchantId, String requestHash) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.merchantId = merchantId;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    void complete(int responseStatus, String responseBody, UUID paymentId) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.paymentId = paymentId;
        this.completedAt = Instant.now();
    }

    boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    boolean isAbandoned(Instant now, Duration timeout) {
        return status == IdempotencyStatus.IN_PROGRESS && createdAt.plus(timeout).isBefore(now);
    }

    Integer getResponseStatus() {
        return responseStatus;
    }

    String getResponseBody() {
        return responseBody;
    }

    UUID getPaymentId() {
        return paymentId;
    }
}
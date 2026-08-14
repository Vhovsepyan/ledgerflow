package am.ankap.ledgerflow.webhook.internal;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery")
class WebhookDeliveryEntity {

    @Id
    private UUID id;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "trace_id", updatable = false, length = 64)
    private String traceId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDeliveryEntity() {
    }

    void markDelivered(int httpStatus) {
        this.status = "DELIVERED";
        this.deliveredAt = Instant.now();
        this.lastStatus = httpStatus;
        this.lastError = null;
        this.nextRetryAt = null;
        this.attempts++;
    }

    void markRetryable(Integer httpStatus, String error, Instant retryAt) {
        this.attempts++;
        this.lastStatus = httpStatus;
        this.lastError = truncate(error);
        this.nextRetryAt = retryAt;
    }

    void markDead(Integer httpStatus, String error) {
        this.status = "DEAD";
        this.attempts++;
        this.lastStatus = httpStatus;
        this.lastError = truncate(error);
        this.nextRetryAt = null;
    }

    UUID getId() {
        return id;
    }

    UUID getEndpointId() {
        return endpointId;
    }

    UUID getEventId() {
        return eventId;
    }

    String getEventType() {
        return eventType;
    }

    String getPayload() {
        return payload;
    }

    int getAttempts() {
        return attempts;
    }

    String getTraceId() {
        return traceId;
    }

    private static String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 500));
    }
}
package am.ankap.ledgerflow.outbox.internal;

import am.ankap.ledgerflow.outbox.OutboxRecord;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "sequence_no", insertable = false, updatable = false)
    private Long sequenceNo;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEventEntity() {
    }

    OutboxEventEntity(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.publishAttempts = 0;
    }

    void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    void markFailed(String error) {
        this.publishAttempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    OutboxRecord toRecord() {
        return new OutboxRecord(id, sequenceNo, aggregateType, aggregateId, eventType, payload, createdAt);
    }
}
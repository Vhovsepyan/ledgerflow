package am.ankap.ledgerflow.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxRecord(
        UUID id,
        long sequenceNo,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt) {

    /** Events for the same aggregate must stay in order, so the aggregate is the key. */
    public String partitionKey() {
        return aggregateId.toString();
    }
}
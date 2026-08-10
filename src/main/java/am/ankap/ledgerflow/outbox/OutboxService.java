package am.ankap.ledgerflow.outbox;

import java.util.UUID;

public interface OutboxService {

    /**
     * Records an event for later publication.
     *
     * MUST be called inside the caller's transaction — that is the whole point.
     * The event and the state change commit together or not at all.
     */
    void append(String aggregateType, UUID aggregateId, String eventType, Object payload);
}
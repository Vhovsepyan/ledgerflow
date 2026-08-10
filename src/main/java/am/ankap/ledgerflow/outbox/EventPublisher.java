package am.ankap.ledgerflow.outbox;

public interface EventPublisher {

    /**
     * Sends one event to the outside world.
     * Throwing means "not published" — the relay will try again.
     */
    void publish(OutboxRecord record);
}
package am.ankap.ledgerflow.payment.internal;

class IdempotencyKeyInUseException extends RuntimeException {

    IdempotencyKeyInUseException(String key) {
        super("Idempotency key '%s' is currently being processed".formatted(key));
    }
}
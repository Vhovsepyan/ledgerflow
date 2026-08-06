package am.ankap.ledgerflow.payment.internal;

class IdempotencyKeyConflictException extends RuntimeException {

    IdempotencyKeyConflictException(String key) {
        super("Idempotency key '%s' was used with a different request".formatted(key));
    }
}
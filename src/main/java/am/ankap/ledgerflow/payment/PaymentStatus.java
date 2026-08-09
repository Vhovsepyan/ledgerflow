package am.ankap.ledgerflow.payment;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {

    CREATED,
    AUTHORIZATION_PENDING,
    AUTHORIZED,
    CAPTURE_PENDING,
    CAPTURED,
    FAILED,
    CANCELED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(CREATED, Set.of(AUTHORIZATION_PENDING, AUTHORIZED, FAILED, CANCELED));
        ALLOWED_TRANSITIONS.put(AUTHORIZATION_PENDING, Set.of(AUTHORIZED, FAILED, CANCELED));
        ALLOWED_TRANSITIONS.put(AUTHORIZED, Set.of(CAPTURE_PENDING, CAPTURED, FAILED, CANCELED));
        ALLOWED_TRANSITIONS.put(CAPTURE_PENDING, Set.of(CAPTURED, FAILED));
        ALLOWED_TRANSITIONS.put(CAPTURED, Set.of(REFUNDED));
        ALLOWED_TRANSITIONS.put(FAILED, Set.of());
        ALLOWED_TRANSITIONS.put(CANCELED, Set.of());
        ALLOWED_TRANSITIONS.put(REFUNDED, Set.of());
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    public boolean isPending() {
        return this == AUTHORIZATION_PENDING || this == CAPTURE_PENDING;
    }
}
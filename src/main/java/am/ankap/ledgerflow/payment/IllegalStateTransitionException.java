package am.ankap.ledgerflow.payment;

import java.util.UUID;

public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("Payment %s cannot move from %s to %s".formatted(paymentId, from, to));
    }
}
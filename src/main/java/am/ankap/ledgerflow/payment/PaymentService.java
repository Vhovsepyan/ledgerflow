package am.ankap.ledgerflow.payment;

import java.util.UUID;

public interface PaymentService {

    PaymentView create(CreatePaymentCommand command);

    PaymentView authorize(UUID paymentId);

    PaymentView capture(UUID paymentId);

    PaymentView findById(UUID paymentId);
}
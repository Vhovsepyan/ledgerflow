package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.*;
import am.ankap.ledgerflow.psp.PspService;
import am.ankap.ledgerflow.psp.PspCall;
import am.ankap.ledgerflow.shared.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class DefaultPaymentService implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final FeePolicy feePolicy;
    private final PaymentStateWriter stateWriter;
    private final PspService pspService;

    DefaultPaymentService(PaymentRepository paymentRepository,
                          FeePolicy feePolicy,
                          PaymentStateWriter stateWriter,
                          PspService pspService) {
        this.paymentRepository = paymentRepository;
        this.feePolicy = feePolicy;
        this.stateWriter = stateWriter;
        this.pspService = pspService;
    }

    @Override
    @Transactional
    public PaymentView create(CreatePaymentCommand command) {
        Money fee = feePolicy.feeFor(command.amount());
        PaymentEntity payment = paymentRepository.save(new PaymentEntity(
                UUID.randomUUID(), command.merchantId(), command.merchantRef(), command.amount(), fee));
        return toView(PaymentSnapshot.of(payment));
    }

    @Override
    public PaymentView authorize(UUID paymentId) {
        PaymentSnapshot pending = stateWriter.markPending(paymentId, PaymentStatus.AUTHORIZATION_PENDING);

        PspCall call = pspService.authorize(
                "payment-" + paymentId, pending.amount(), "auth:" + paymentId);

        stateWriter.recordAttempt(paymentId, "AUTHORIZE", call);
        return toView(stateWriter.applyAuthorizeResult(paymentId, call.result()));
    }

    @Override
    public PaymentView capture(UUID paymentId) {
        PaymentSnapshot pending = stateWriter.markPending(paymentId, PaymentStatus.CAPTURE_PENDING);

        if (pending.pspReference() == null) {
            throw new IllegalStateException("Payment %s has no provider reference".formatted(paymentId));
        }

        PspCall call = pspService.capture(
                pending.pspReference(), pending.amount(), "capture:" + paymentId);

        stateWriter.recordAttempt(paymentId, "CAPTURE", call);
        return toView(stateWriter.applyCaptureResult(paymentId, call.result()));
    }

    @Override
    public PaymentView findById(UUID paymentId) {
        return toView(stateWriter.snapshot(paymentId));
    }

    private static PaymentView toView(PaymentSnapshot snapshot) {
        return new PaymentView(
                snapshot.id(), snapshot.merchantId(), snapshot.merchantRef(),
                snapshot.status(), snapshot.amount(), snapshot.fee(),
                snapshot.merchantNet(), snapshot.failureReason(), snapshot.createdAt());
    }
}
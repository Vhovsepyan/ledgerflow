package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentStatus;
import am.ankap.ledgerflow.psp.PspService;
import am.ankap.ledgerflow.psp.PspCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class PaymentVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentVerificationJob.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final int ALERT_AFTER_ATTEMPTS = 8;
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentStateWriter stateWriter;
    private final PspService pspService;

    PaymentVerificationJob(PaymentRepository paymentRepository,
                           PaymentStateWriter stateWriter,
                           PspService pspService) {
        this.paymentRepository = paymentRepository;
        this.stateWriter = stateWriter;
        this.pspService = pspService;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.verification.interval:10s}")
    void verifyPendingPayments() {
        List<UUID> due = paymentRepository.findDueForVerification(
                List.of(PaymentStatus.AUTHORIZATION_PENDING, PaymentStatus.CAPTURE_PENDING),
                Instant.now(),
                Limit.of(BATCH_SIZE));

        for (UUID paymentId : due) {
            try {
                verify(paymentId);
            } catch (RuntimeException e) {
                log.error("Verification failed for payment {}", paymentId, e);
            }
        }
    }

    private void verify(UUID paymentId) {
        PaymentSnapshot payment = stateWriter.snapshot(paymentId);
        if (!payment.status().isPending()) {
            return;   // resolved by someone else between the query and now
        }

        PspCall call = pspService.lookupByReference("payment-" + paymentId);
        stateWriter.recordAttempt(paymentId, "LOOKUP", call);

        PaymentSnapshot after = payment.status() == PaymentStatus.AUTHORIZATION_PENDING
                ? stateWriter.applyAuthorizeResult(paymentId, call.result())
                : stateWriter.applyCaptureResult(paymentId, call.result());

        if (after.status().isPending()) {
            Duration delay = backoffFor(payment.verificationAttempts() + 1);
            stateWriter.rescheduleVerification(paymentId, delay);

            if (payment.verificationAttempts() + 1 >= ALERT_AFTER_ATTEMPTS) {
                log.error("Payment {} still unresolved after {} verification attempts - needs a human",
                        paymentId, payment.verificationAttempts() + 1);
            }
        }
    }

    private static Duration backoffFor(int attempt) {
        Duration base = Duration.ofSeconds(15).multipliedBy(1L << Math.min(attempt - 1, 10));
        return base.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : base;
    }
}
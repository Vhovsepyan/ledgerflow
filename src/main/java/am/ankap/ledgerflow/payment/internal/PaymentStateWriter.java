package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.ledger.AccountType;
import am.ankap.ledgerflow.ledger.LedgerService;
import am.ankap.ledgerflow.ledger.LedgerTransactionRequest;
import am.ankap.ledgerflow.payment.PaymentNotFoundException;
import am.ankap.ledgerflow.payment.PaymentStatus;
import am.ankap.ledgerflow.psp.PspResult;
import am.ankap.ledgerflow.psp.PspCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Service
class PaymentStateWriter {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateWriter.class);
    private static final Duration FIRST_VERIFICATION_DELAY = Duration.ofSeconds(15);

    private final PaymentRepository paymentRepository;
    private final PspAttemptRepository attemptRepository;
    private final LedgerService ledgerService;

    PaymentStateWriter(PaymentRepository paymentRepository,
                       PspAttemptRepository attemptRepository,
                       LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    PaymentSnapshot snapshot(UUID paymentId) {
        return toSnapshot(require(paymentId));
    }

    /** Commits the pending state BEFORE the provider is called, so a crash is recoverable. */
    @Transactional
    PaymentSnapshot markPending(UUID paymentId, PaymentStatus pendingStatus) {
        PaymentEntity payment = require(paymentId);
        payment.markPending(pendingStatus, Instant.now().plus(FIRST_VERIFICATION_DELAY));
        return toSnapshot(payment);
    }

    @Transactional
    PaymentSnapshot applyAuthorizeResult(UUID paymentId, PspResult result) {
        PaymentEntity payment = require(paymentId);

        switch (result) {
            case PspResult.Authorized authorized ->
                    payment.settleAs(PaymentStatus.AUTHORIZED, authorized.pspReference());
            case PspResult.Captured captured ->
                    payment.settleAs(PaymentStatus.AUTHORIZED, captured.pspReference());
            case PspResult.Declined declined -> {
                payment.settleAs(PaymentStatus.FAILED, declined.pspReference());
                payment.setFailureReason(declined.reason());
            }
            case PspResult.Failed failed -> {
                payment.settleAs(PaymentStatus.FAILED, null);
                payment.setFailureReason(failed.reason());
            }
            case PspResult.Unknown unknown ->
                    log.warn("Payment {} authorization unresolved: {}", paymentId, unknown.reason());
        }
        return toSnapshot(payment);
    }

    @Transactional
    PaymentSnapshot applyCaptureResult(UUID paymentId, PspResult result) {
        PaymentEntity payment = require(paymentId);

        switch (result) {
            case PspResult.Captured captured -> {
                payment.settleAs(PaymentStatus.CAPTURED, captured.pspReference());
                postCaptureToLedger(payment);
            }
            case PspResult.Authorized ignored ->
                    log.warn("Payment {} capture not applied by provider yet", paymentId);
            case PspResult.Declined declined -> {
                payment.settleAs(PaymentStatus.FAILED, declined.pspReference());
                payment.setFailureReason(declined.reason());
            }
            case PspResult.Failed failed -> {
                payment.settleAs(PaymentStatus.FAILED, null);
                payment.setFailureReason(failed.reason());
            }
            case PspResult.Unknown unknown ->
                    log.warn("Payment {} capture unresolved: {}", paymentId, unknown.reason());
        }
        return toSnapshot(payment);
    }

    @Transactional
    void rescheduleVerification(UUID paymentId, Duration delay) {
        require(paymentId).scheduleNextVerification(Instant.now().plus(delay));
    }

    @Transactional
    void recordAttempt(UUID paymentId, String operation, PspCall call) {
        PspResult result = call.result();

        String outcome = switch (result) {
            case PspResult.Authorized ignored -> "AUTHORIZED";
            case PspResult.Captured ignored -> "CAPTURED";
            case PspResult.Declined ignored -> "DECLINED";
            case PspResult.Failed ignored -> "FAILED";
            case PspResult.Unknown ignored -> "UNKNOWN";
        };
        String pspReference = switch (result) {
            case PspResult.Authorized a -> a.pspReference();
            case PspResult.Captured c -> c.pspReference();
            case PspResult.Declined d -> d.pspReference();
            default -> null;
        };
        String detail = switch (result) {
            case PspResult.Declined d -> d.reason();
            case PspResult.Failed f -> f.reason();
            case PspResult.Unknown u -> u.reason();
            default -> null;
        };

        attemptRepository.save(new PspAttemptEntity(
                paymentId, operation, call.attempts(), outcome, pspReference, detail, call.latencyMs()));
    }

    private void postCaptureToLedger(PaymentEntity payment) {
        Currency currency = payment.getAmount().currency();
        ledgerService.openAccount(LedgerAccounts.pspClearing(currency), AccountType.ASSET, currency);
        ledgerService.openAccount(LedgerAccounts.merchantPayable(payment.getMerchantId(), currency),
                AccountType.LIABILITY, currency);
        ledgerService.openAccount(LedgerAccounts.feeRevenue(currency), AccountType.REVENUE, currency);

        ledgerService.post(LedgerTransactionRequest
                .reference("payment:%s:capture".formatted(payment.getId()))
                .description("Capture payment " + payment.getId())
                .debit(LedgerAccounts.pspClearing(currency), payment.getAmount())
                .credit(LedgerAccounts.merchantPayable(payment.getMerchantId(), currency),
                        payment.getMerchantNet())
                .credit(LedgerAccounts.feeRevenue(currency), payment.getFee())
                .build());
    }

    private PaymentEntity require(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private static PaymentSnapshot toSnapshot(PaymentEntity payment) {
        return new PaymentSnapshot(payment.getId(), payment.getMerchantId(), payment.getMerchantRef(),
                payment.getStatus(), payment.getAmount(), payment.getFee(), payment.getPspReference(),
                payment.getFailureReason(), payment.getVerificationAttempts(), payment.getCreatedAt());
    }
}
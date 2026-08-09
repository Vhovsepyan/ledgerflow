package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentStatus;
import am.ankap.ledgerflow.shared.Money;

import java.time.Instant;
import java.util.UUID;

record PaymentSnapshot(
        UUID id,
        UUID merchantId,
        String merchantRef,
        PaymentStatus status,
        Money amount,
        Money fee,
        String pspReference,
        String failureReason,
        int verificationAttempts,
        Instant createdAt) {

    static PaymentSnapshot of(PaymentEntity payment) {
        return new PaymentSnapshot(
                payment.getId(),
                payment.getMerchantId(),
                payment.getMerchantRef(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getFee(),
                payment.getPspReference(),
                payment.getFailureReason(),
                payment.getVerificationAttempts(),
                payment.getCreatedAt());
    }

    Money merchantNet() {
        return amount.minus(fee);
    }
}
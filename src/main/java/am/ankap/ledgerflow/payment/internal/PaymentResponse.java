package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentStatus;
import am.ankap.ledgerflow.payment.PaymentView;

import java.time.Instant;
import java.util.UUID;

record PaymentResponse(
        UUID id,
        UUID merchantId,
        String merchantRef,
        PaymentStatus status,
        long amountMinor,
        long feeMinor,
        long merchantNetMinor,
        String currency,
        String failureReason,
        Instant createdAt) {

    static PaymentResponse from(PaymentView view) {
        return new PaymentResponse(
                view.id(),
                view.merchantId(),
                view.merchantRef(),
                view.status(),
                view.amount().minorUnits(),
                view.fee().minorUnits(),
                view.merchantNet().minorUnits(),
                view.amount().currency().getCurrencyCode(),
                view.failureReason(),
                view.createdAt());
    }
}
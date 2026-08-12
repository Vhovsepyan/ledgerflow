package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.payment.PaymentEvents;

import java.time.Instant;

final class PaymentEventFactory {

    private static final int CURRENT_VERSION = 1;

    private PaymentEventFactory() {
    }

    static PaymentEvents.PaymentAuthorized authorized(PaymentEntity payment) {
        return new PaymentEvents.PaymentAuthorized(
                CURRENT_VERSION,
                payment.getId(),
                payment.getMerchantId(),
                payment.getMerchantRef(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currency().getCurrencyCode(),
                payment.getPspReference(),
                Instant.now());
    }

    static PaymentEvents.PaymentCaptured captured(PaymentEntity payment) {
        return new PaymentEvents.PaymentCaptured(
                CURRENT_VERSION,
                payment.getId(),
                payment.getMerchantId(),
                payment.getMerchantRef(),
                payment.getAmount().minorUnits(),
                payment.getFee().minorUnits(),
                payment.getMerchantNet().minorUnits(),
                payment.getAmount().currency().getCurrencyCode(),
                payment.getPspReference(),
                Instant.now());
    }

    static PaymentEvents.PaymentFailed failed(PaymentEntity payment, String reason) {
        return new PaymentEvents.PaymentFailed(
                CURRENT_VERSION,
                payment.getId(),
                payment.getMerchantId(),
                payment.getMerchantRef(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currency().getCurrencyCode(),
                reason,
                Instant.now());
    }
}
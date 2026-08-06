package am.ankap.ledgerflow.payment;

import am.ankap.ledgerflow.shared.Money;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        UUID id,
        UUID merchantId,
        String merchantRef,
        PaymentStatus status,
        Money amount,
        Money fee,
        Money merchantNet,
        String failureReason,
        Instant createdAt) {
}
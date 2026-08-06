package am.ankap.ledgerflow.payment;

import am.ankap.ledgerflow.shared.Money;

import java.util.Objects;
import java.util.UUID;

public record CreatePaymentCommand(UUID merchantId, String merchantRef, Money amount) {

    public CreatePaymentCommand {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
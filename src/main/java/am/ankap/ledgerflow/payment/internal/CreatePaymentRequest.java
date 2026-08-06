package am.ankap.ledgerflow.payment.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

record CreatePaymentRequest(
        @NotNull @Positive Long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 255) String merchantRef) {
}
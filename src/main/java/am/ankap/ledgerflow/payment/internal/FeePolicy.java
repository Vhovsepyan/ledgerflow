package am.ankap.ledgerflow.payment.internal;

import am.ankap.ledgerflow.shared.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
class FeePolicy {

    private static final BigDecimal PERCENTAGE = new BigDecimal("0.029");
    private static final long FIXED_MINOR = 30L;

    Money feeFor(Money amount) {
        BigDecimal variablePart = BigDecimal.valueOf(amount.minorUnits())
                .multiply(PERCENTAGE)
                .setScale(0, RoundingMode.HALF_UP);

        long fee = variablePart.longValueExact() + FIXED_MINOR;
        return new Money(Math.min(fee, amount.minorUnits()), amount.currency());
    }
}
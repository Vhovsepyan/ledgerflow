package am.ankap.ledgerflow.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount of money in a single currency.
 * Stored as minor units (cents for USD, whole yen for JPY).
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Parses a decimal string, e.g. parse("12.34", "USD") -> 1234 minor units.
     * Rejects input with more decimal places than the currency allows.
     */
    public static Money parse(String amount, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        int fractionDigits = currency.getDefaultFractionDigits();
        BigDecimal value = new BigDecimal(amount).setScale(fractionDigits, RoundingMode.UNNECESSARY);
        return new Money(value.movePointRight(fractionDigits).longValueExact(), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    /** For display and for API responses only — never for calculation. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
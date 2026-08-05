package am.ankap.ledgerflow.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesDecimalStringIntoMinorUnits() {
        assertThat(Money.parse("12.34", "USD").minorUnits()).isEqualTo(1234L);
        assertThat(Money.parse("0.05", "USD").minorUnits()).isEqualTo(5L);
        assertThat(Money.parse("-3.00", "USD").minorUnits()).isEqualTo(-300L);
    }

    @Test
    void respectsCurrencyFractionDigits() {
        assertThat(Money.parse("1200", "JPY").minorUnits()).isEqualTo(1200L);
        assertThat(Money.parse("1.234", "KWD").minorUnits()).isEqualTo(1234L);
    }

    @Test
    void rejectsTooManyDecimalPlaces() {
        assertThatThrownBy(() -> Money.parse("12.345", "USD"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void addsAndSubtractsWithinSameCurrency() {
        Money ten = Money.parse("10.00", "USD");
        Money three = Money.parse("3.00", "USD");

        assertThat(ten.plus(three)).isEqualTo(Money.parse("13.00", "USD"));
        assertThat(ten.minus(three)).isEqualTo(Money.parse("7.00", "USD"));
        assertThat(ten.minus(ten).isZero()).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        Money usd = Money.parse("10.00", "USD");
        Money eur = Money.parse("10.00", "EUR");

        assertThatThrownBy(() -> usd.plus(eur))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void equalityIsExact() {
        assertThat(Money.parse("2.00", "USD")).isEqualTo(Money.of(200L, "USD"));
    }

    @Test
    void convertsBackToDecimalForDisplay() {
        assertThat(Money.parse("12.34", "USD").toDecimal())
                .isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(Money.parse("1200", "JPY").toString()).isEqualTo("1200 JPY");
    }
}
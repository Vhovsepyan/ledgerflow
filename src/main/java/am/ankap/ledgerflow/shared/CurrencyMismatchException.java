package am.ankap.ledgerflow.shared;

import java.util.Currency;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected %s but was %s"
                .formatted(expected.getCurrencyCode(), actual.getCurrencyCode()));
    }
}
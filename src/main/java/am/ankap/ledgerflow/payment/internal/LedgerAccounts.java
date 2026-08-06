package am.ankap.ledgerflow.payment.internal;

import java.util.Currency;
import java.util.UUID;

final class LedgerAccounts {

    private LedgerAccounts() {
    }

    static String pspClearing(Currency currency) {
        return "PSP_CLEARING:" + currency.getCurrencyCode();
    }

    static String merchantPayable(UUID merchantId, Currency currency) {
        return "MERCHANT_PAYABLE:%s:%s".formatted(merchantId, currency.getCurrencyCode());
    }

    static String feeRevenue(Currency currency) {
        return "FEE_REVENUE:" + currency.getCurrencyCode();
    }
}
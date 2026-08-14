package am.ankap.ledgerflow.recon.internal;

import java.util.Currency;

final class SettlementAccounts {

    private SettlementAccounts() {
    }

    /** Our bank account — where the provider's payout actually lands. */
    static String bank(Currency currency) {
        return "BANK:" + currency.getCurrencyCode();
    }

    /** Must match the key the payment module uses, or the two sides never meet. */
    static String pspClearing(Currency currency) {
        return "PSP_CLEARING:" + currency.getCurrencyCode();
    }
}
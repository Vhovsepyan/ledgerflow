package am.ankap.ledgerflow.recon;

import am.ankap.ledgerflow.shared.Money;

import java.time.LocalDate;

public record SettlementLine(
        String reference,
        String pspReference,
        Money amount,
        LocalDate settledOn) {
}
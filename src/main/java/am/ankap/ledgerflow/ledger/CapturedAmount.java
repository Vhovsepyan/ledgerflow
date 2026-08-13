package am.ankap.ledgerflow.ledger;

import java.util.Currency;
import java.util.UUID;

/** How much was captured for one source, as recorded in the clearing account. */
public record CapturedAmount(UUID sourceId, long amountMinor, Currency currency) {
}
package am.ankap.ledgerflow.psp;

import am.ankap.ledgerflow.shared.Money;

public interface PspService {

    PspResult authorize(String reference, Money amount, String idempotencyKey);

    PspResult capture(String pspReference, Money amount, String idempotencyKey);

    /** Asks the provider what really happened. Used to resolve an Unknown outcome. */
    PspResult lookupByReference(String reference);
}
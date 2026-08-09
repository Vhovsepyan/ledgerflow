package am.ankap.ledgerflow.psp;

import am.ankap.ledgerflow.shared.Money;

public interface PspService {

    PspCall authorize(String reference, Money amount, String idempotencyKey);

    PspCall capture(String pspReference, Money amount, String idempotencyKey);

    /** Asks the provider what really happened. Used to resolve an Unknown outcome. */
    PspCall lookupByReference(String reference);
}
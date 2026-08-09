package am.ankap.ledgerflow.psp;

/**
 * The outcome of a provider call.
 *
 * The important distinction is between Failed and Unknown:
 * Failed means the request never reached the provider, so nothing happened.
 * Unknown means it did reach them and may have been applied — we cannot tell.
 */
public sealed interface PspResult {

    record Authorized(String pspReference) implements PspResult {
    }

    record Captured(String pspReference, long capturedMinor) implements PspResult {
    }

    record Declined(String pspReference, String reason) implements PspResult {
    }

    /** Definitely did not happen. Safe to fail the payment. */
    record Failed(String reason) implements PspResult {
    }

    /** May or may not have happened. Must be verified later. */
    record Unknown(String reason) implements PspResult {
    }
}
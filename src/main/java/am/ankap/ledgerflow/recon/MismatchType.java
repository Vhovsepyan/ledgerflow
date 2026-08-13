package am.ankap.ledgerflow.recon;

public enum MismatchType {

    /** The provider settled something we have no ledger entry for. */
    MISSING_IN_LEDGER,

    /** We captured it, the provider has not settled it. */
    MISSING_IN_PROVIDER,

    /** Both have it, with different amounts. */
    AMOUNT_MISMATCH
}
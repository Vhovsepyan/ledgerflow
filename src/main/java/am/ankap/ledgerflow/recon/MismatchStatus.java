package am.ankap.ledgerflow.recon;

public enum MismatchStatus {

    /** Found, not yet judged by a human. */
    OPEN,

    /** A human decided what happened and recorded why. */
    RESOLVED,

    /** A human judged it not worth acting on. */
    IGNORED
}
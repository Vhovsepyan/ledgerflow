package am.ankap.ledgerflow.payment.internal;

import java.util.UUID;

record ClaimResult(Outcome outcome, Integer responseStatus, String responseBody, UUID paymentId) {

    enum Outcome {
        /** This request owns the key — go do the work. */
        OWNED,
        /** Already finished — replay the stored response. */
        REPLAY,
        /** Same key, different request body. */
        CONFLICT,
        /** Another request holds the key right now. */
        IN_PROGRESS
    }

    static ClaimResult owned(UUID paymentId) {
        return new ClaimResult(Outcome.OWNED, null, null, paymentId);
    }

    static ClaimResult replay(Integer responseStatus, String responseBody) {
        return new ClaimResult(Outcome.REPLAY, responseStatus, responseBody, null);
    }

    static ClaimResult conflict() {
        return new ClaimResult(Outcome.CONFLICT, null, null, null);
    }

    static ClaimResult inProgress() {
        return new ClaimResult(Outcome.IN_PROGRESS, null, null, null);
    }
}
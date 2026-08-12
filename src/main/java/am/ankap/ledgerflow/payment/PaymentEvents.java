package am.ankap.ledgerflow.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * The published event schema. This is a public API — consumers depend on it.
 *
 * Rules:
 *  - additive changes only; never rename or remove a field
 *  - bump `version` when the meaning of an existing field changes
 *  - no internal or operational detail (retry counts, provider errors)
 */
public final class PaymentEvents {

    public static final String AGGREGATE_TYPE = "payment";

    private PaymentEvents() {
    }

    public record PaymentAuthorized(
            int version,
            UUID paymentId,
            UUID merchantId,
            String merchantRef,
            long amountMinor,
            String currency,
            String pspReference,
            Instant occurredAt) {

        public static final String TYPE = "payment.authorized";
    }

    public record PaymentCaptured(
            int version,
            UUID paymentId,
            UUID merchantId,
            String merchantRef,
            long amountMinor,
            long feeMinor,
            long merchantNetMinor,
            String currency,
            String pspReference,
            Instant occurredAt) {

        public static final String TYPE = "payment.captured";
    }

    public record PaymentFailed(
            int version,
            UUID paymentId,
            UUID merchantId,
            String merchantRef,
            long amountMinor,
            String currency,
            String reason,
            Instant occurredAt) {

        public static final String TYPE = "payment.failed";
    }
}
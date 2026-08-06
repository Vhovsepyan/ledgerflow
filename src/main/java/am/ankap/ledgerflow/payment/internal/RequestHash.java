package am.ankap.ledgerflow.payment.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class RequestHash {

    private RequestHash() {
    }

    static String of(UUID merchantId, CreatePaymentRequest request) {
        String canonical = "%s|%d|%s|%s".formatted(
                merchantId,
                request.amountMinor(),
                request.currency(),
                request.merchantRef() == null ? "" : request.merchantRef());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
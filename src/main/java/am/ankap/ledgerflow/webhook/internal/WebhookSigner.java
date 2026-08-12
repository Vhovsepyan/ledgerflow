package am.ankap.ledgerflow.webhook.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    /**
     * Signs timestamp + payload, not just the payload.
     * Including the timestamp lets the receiver reject replayed deliveries.
     */
    static String sign(String secret, long timestampSeconds, String payload) {
        String signedContent = timestampSeconds + "." + payload;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }
}
package de.sgart.identity.adapter.out;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one HMAC-SHA256 computation shared by the recovery-code hasher and the recovery-address
 * digester, so the keyed-hash mechanics (and the fail-fast on a blank secret) exist once.
 */
final class HmacSha256 {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    /** @param secretPropertyName named in the failure message so a misconfiguration is easy to find. */
    HmacSha256(String secret, String secretPropertyName) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(secretPropertyName
                    + " must be configured (a blank/missing HMAC secret is never acceptable)");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    String hexDigestOf(String message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException cause) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", cause);
        }
    }
}

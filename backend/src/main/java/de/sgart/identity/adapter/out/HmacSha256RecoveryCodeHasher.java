package de.sgart.identity.adapter.out;

import de.sgart.identity.application.RecoveryCodeHasher;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Production {@link RecoveryCodeHasher} (Story 7.3, design §4) — mirrors {@code
 * collaboration.adapter.out.HmacSha256InviteEmailHasher}'s exact pattern: HMAC-SHA256 with a
 * <strong>stable per-deployment</strong> secret, so a leaked {@code email_recovery_code} table
 * can't be brute-forced back to a live code without the server secret. Fails fast at construction
 * if the secret is blank/unconfigured.
 */
public final class HmacSha256RecoveryCodeHasher implements RecoveryCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public HmacSha256RecoveryCodeHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "sgart.identity.email-recovery.code-hmac-secret must be configured (a blank/missing HMAC secret "
                            + "is never acceptable)");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String hash(String code) {
        Objects.requireNonNull(code, "code must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException cause) {
            throw new IllegalStateException("Failed to compute recovery code HMAC", cause);
        }
    }
}

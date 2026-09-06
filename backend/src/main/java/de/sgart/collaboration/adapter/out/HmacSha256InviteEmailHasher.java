package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.InviteEmailHasher;
import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.collaboration.domain.EmailHmac;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Production {@link InviteEmailHasher} (Story 4.1, AD-6): HMAC-SHA256 over the normalized email
 * using a <strong>stable per-deployment</strong> secret from {@code
 * sgart.invite.email-hmac-secret} — never a per-invite salt, so the same email always hashes to the
 * same digest (the AC2 duplicate-pending check depends on this stability). Fails fast at
 * construction if the secret is blank/unconfigured, rather than silently hashing with an empty key.
 */
public final class HmacSha256InviteEmailHasher implements InviteEmailHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public HmacSha256InviteEmailHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "sgart.invite.email-hmac-secret must be configured (a blank/missing HMAC secret is never acceptable)");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public EmailHmac hash(NormalizedEmail normalizedEmail) {
        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(normalizedEmail.value().getBytes(StandardCharsets.UTF_8));
            return new EmailHmac(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException | InvalidKeyException cause) {
            throw new IllegalStateException("Failed to compute invite email HMAC", cause);
        }
    }
}

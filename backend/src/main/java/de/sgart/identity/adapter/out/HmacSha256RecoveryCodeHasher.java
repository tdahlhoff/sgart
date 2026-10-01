package de.sgart.identity.adapter.out;

import de.sgart.identity.application.RecoveryCodeHasher;
import java.util.Objects;

/**
 * Production {@link RecoveryCodeHasher} (Story 7.3, design §4): HMAC-SHA256 with a
 * <strong>stable per-deployment</strong> secret, so a leaked {@code email_recovery_code} table
 * can't be brute-forced back to a live code without the server secret. Fails fast at construction
 * if the secret is blank/unconfigured.
 */
public final class HmacSha256RecoveryCodeHasher implements RecoveryCodeHasher {

    private final HmacSha256 hmacSha256;

    public HmacSha256RecoveryCodeHasher(String secret) {
        this.hmacSha256 = new HmacSha256(secret, "sgart.identity.email-recovery.code-hmac-secret");
    }

    @Override
    public String hash(String code) {
        Objects.requireNonNull(code, "code must not be null");
        return hmacSha256.hexDigestOf(code);
    }
}

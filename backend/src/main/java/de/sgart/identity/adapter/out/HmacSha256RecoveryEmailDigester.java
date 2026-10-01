package de.sgart.identity.adapter.out;

import de.sgart.identity.application.RecoveryEmailDigester;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.util.Objects;

/**
 * Production {@link RecoveryEmailDigester}: HMAC-SHA256 over the normalized address with a
 * per-deployment pepper that lives outside the database. Without the pepper the digests of a
 * leaked table cannot be matched against guessed addresses.
 */
public final class HmacSha256RecoveryEmailDigester implements RecoveryEmailDigester {

    private final HmacSha256 hmacSha256;

    public HmacSha256RecoveryEmailDigester(String pepper) {
        this.hmacSha256 = new HmacSha256(pepper, "sgart.identity.email-recovery.address-pepper");
    }

    @Override
    public RecoveryEmailDigest digest(String normalizedAddress) {
        Objects.requireNonNull(normalizedAddress, "normalizedAddress must not be null");
        return new RecoveryEmailDigest(hmacSha256.hexDigestOf(normalizedAddress));
    }
}

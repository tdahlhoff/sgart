package de.sgart.identity.domain;

import java.util.Objects;

/**
 * The keyed one-way digest of a normalized recovery address. It is the only handle SGART keeps for
 * an address: the plaintext is never stored, so a leaked table reveals no addresses, while the
 * person typing the address again lets recovery find the matching bindings.
 */
public record RecoveryEmailDigest(String value) {

    public RecoveryEmailDigest {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

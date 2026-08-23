package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, per-household pseudonym for a person. Lives in {@code shared}, not
 * {@code identity.domain}, because other contexts (e.g. Collaboration) reference it and a
 * context's domain must not depend on another context's domain (AD-2).
 *
 * <p>The Identity ACL is the sole minter and sole resolver of a {@code MemberId} (AD-5) — a
 * person who belongs to two households has two unrelated {@code MemberId}s. This type carries no
 * Keycloak identity; see {@code identity.domain.KeycloakUserId}.
 */
public record MemberId(UUID value) {

    public MemberId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static MemberId generate() {
        return new MemberId(UUID.randomUUID());
    }

    public static MemberId fromString(String value) {
        return new MemberId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

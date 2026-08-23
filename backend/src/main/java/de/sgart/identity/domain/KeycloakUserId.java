package de.sgart.identity.domain;

import java.util.Objects;

/**
 * The opaque subject id Keycloak assigns to a person, taken only from a validated JWT's
 * {@code sub} claim (AR10). A plain string wrapper — no {@code org.keycloak} import — so
 * {@code identity.domain} stays free of any identity-provider or framework type (AD-1).
 *
 * <p>Only the Identity ACL may hold this id (AD-5); it never leaves the {@code identity} context
 * and is never referenced by another bounded context's domain.
 */
public record KeycloakUserId(String value) {

    public KeycloakUserId {
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

package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, server-generated identity for aggregates and entities.
 *
 * <p>Ids are UUID-backed and carry no domain meaning. Keycloak user ids, emails, and names never
 * appear here — people are referenced only by a household-scoped pseudonym (AD-5).
 */
public record Identifier(UUID value) {

    public Identifier {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static Identifier generate() {
        return new Identifier(UUID.randomUUID());
    }

    public static Identifier fromString(String value) {
        return new Identifier(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

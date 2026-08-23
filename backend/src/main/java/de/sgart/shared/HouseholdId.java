package de.sgart.shared;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for a household. Lives in {@code shared} because multiple bounded
 * contexts (Identity, Collaboration, Store Reference, …) reference a household by id without
 * depending on each other's domain (AD-2).
 */
public record HouseholdId(UUID value) {

    public HouseholdId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static HouseholdId generate() {
        return new HouseholdId(UUID.randomUUID());
    }

    /**
     * Derives a stable household id from a {@code seed}: the same seed always yields the same id (a
     * pure function). The create-household flow seeds this with {@code (keycloakUserId, commandId)}
     * so a retried create converges on one household instead of minting duplicates (Story 1.6
     * Clarification 5). Implemented as a name-based (version 3) UUID over the seed bytes.
     */
    public static HouseholdId deterministicFrom(String seed) {
        Objects.requireNonNull(seed, "seed must not be null");
        return new HouseholdId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }

    public static HouseholdId fromString(String value) {
        return new HouseholdId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

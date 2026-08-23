package de.sgart.shared;

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

    public static HouseholdId fromString(String value) {
        return new HouseholdId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

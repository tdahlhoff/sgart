package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for a store. Lives in {@code shared}, like {@link HouseholdId}, because
 * multiple bounded contexts reference a store by id without depending on each other's domain: the
 * Collaboration {@code Household} owns the store as an entity (AD-10), while later item-assignment,
 * trip, and reroute pickers (Epics 2–3) reference it by this opaque id (AD-2).
 *
 * <p>UUID-backed and carrying no domain meaning (AD-5, §2). Generated <em>client-side</em> and
 * carried in the add-store command envelope, so the command response needs no body — the client
 * already knows the id it minted (read-your-writes without waiting on a projection).
 */
public record StoreId(UUID value) {

    public StoreId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static StoreId generate() {
        return new StoreId(UUID.randomUUID());
    }

    public static StoreId fromString(String value) {
        return new StoreId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

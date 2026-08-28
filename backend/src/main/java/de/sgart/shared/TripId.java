package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for a shopping trip. Lives in {@code shared}, like {@link
 * ShoppingListId} and {@link StoreId}, because the Collaboration {@code ShoppingTrip} aggregate
 * owns it while the {@code ShoppingList} aggregate's {@code TripStartedForList} event carries it
 * as an opaque reference (AD-2).
 *
 * <p>UUID-backed and carrying no domain meaning (AD-5, §2). Generated <em>client-side</em> and
 * carried in the start-trip command envelope, so the command response needs no body — the client
 * already knows the id it minted (read-your-writes without waiting on a projection).
 */
public record TripId(UUID value) {

    public TripId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static TripId generate() {
        return new TripId(UUID.randomUUID());
    }

    public static TripId fromString(String value) {
        return new TripId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

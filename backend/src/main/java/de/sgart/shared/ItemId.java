package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for an item on a shopping list. Lives in {@code shared}, like {@link
 * StoreId}, because multiple bounded contexts may reference an item by id without depending on
 * each other's domain: the Collaboration {@code ShoppingList} owns the item as an entity (AD-10),
 * while later store-assignment and trip pickers (Epics 2–3) may reference it by this opaque id
 * (AD-2).
 *
 * <p>UUID-backed and carrying no domain meaning (AD-5, §2). Generated <em>client-side</em> and
 * carried in the add-item command envelope, so the command response needs no body — the client
 * already knows the id it minted (read-your-writes without waiting on a projection).
 */
public record ItemId(UUID value) {

    public ItemId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ItemId generate() {
        return new ItemId(UUID.randomUUID());
    }

    public static ItemId fromString(String value) {
        return new ItemId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

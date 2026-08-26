package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for a shopping list. Lives in {@code shared}, like {@link HouseholdId}
 * and {@link StoreId}, because multiple bounded contexts reference a list by id without depending
 * on each other's domain: the Collaboration {@code ShoppingList} aggregate owns it (Story 2.1), and
 * later Epic 3 trip references point at it by this opaque id (AD-2).
 *
 * <p>UUID-backed and carrying no domain meaning (AD-5, §2). Generated <em>client-side</em> and
 * carried in the create-list command envelope, so the command response needs no body — the client
 * already knows the id it minted (read-your-writes without waiting on a projection).
 */
public record ShoppingListId(UUID value) {

    public ShoppingListId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ShoppingListId generate() {
        return new ShoppingListId(UUID.randomUUID());
    }

    public static ShoppingListId fromString(String value) {
        return new ShoppingListId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

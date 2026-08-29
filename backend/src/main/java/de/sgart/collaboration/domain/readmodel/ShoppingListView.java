package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;

/**
 * A shopping list as held in the read model (AD-4) — id, the optional display name, its lifecycle
 * status, its item count (Story 2.1; {@code itemCount} added Story 2.3, AC7), and its currently
 * active trip (Story 3.2, AC4, Cl. 4). {@code name} is nullable: an unnamed list has no display
 * name, and the client derives „Liste N" (AC2). Carries {@code status} so 2.2's Offen/Erledigt
 * split can read through the same port without a shape change. {@code itemCount} is derived
 * ({@code COUNT} over {@code item_read_model}), not a stored counter — the item rows stay the
 * single source of truth. {@code activeTripId} is {@code null} for an {@code OPEN} list — the
 * navigation key list→trip. Read-only projection shape, distinct from the aggregate's internal
 * state.
 */
public record ShoppingListView(
        ShoppingListId listId, ShoppingListName name, ListStatus status, int itemCount, TripId activeTripId) {}

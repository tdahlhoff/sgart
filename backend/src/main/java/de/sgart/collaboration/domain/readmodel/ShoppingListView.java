package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.ShoppingListId;

/**
 * A shopping list as held in the read model (AD-4) — id, the optional display name, and its
 * lifecycle status (Story 2.1). {@code name} is nullable: an unnamed list has no display name, and
 * the client derives „Liste N" (AC2). Carries {@code status} so 2.2's Offen/Erledigt split can read
 * through the same port without a shape change. Read-only projection shape, distinct from the
 * aggregate's internal state.
 */
public record ShoppingListView(ShoppingListId listId, ShoppingListName name, ListStatus status) {}

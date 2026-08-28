package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.Quantity;
import de.sgart.shared.StoreId;

/**
 * A household's last-used attributes for a previously-used item name (Story 2.5, AC2/AC6; Story
 * 2.6, AC6) — no {@code itemId}: a suggestion is not an item, the client mints a fresh {@code
 * itemId} when it adds. {@code note} and {@code defaultStore} are nullable, mirroring {@link
 * ItemView}; {@code defaultStore} is the name's last-used store, written only by {@code
 * ItemAssignedToStore}'s projection (Cl. 6/7).
 */
public record ItemSuggestionView(ItemName name, ItemNote note, Quantity quantity, StoreId defaultStore) {}

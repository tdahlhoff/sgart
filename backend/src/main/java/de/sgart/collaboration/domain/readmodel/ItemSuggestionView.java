package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.Quantity;

/**
 * A household's last-used attributes for a previously-used item name (Story 2.5, AC2/AC6) — no
 * {@code itemId}: a suggestion is not an item, the client mints a fresh {@code itemId} when it
 * adds. {@code note} is nullable, mirroring {@link ItemView}.
 */
public record ItemSuggestionView(ItemName name, ItemNote note, Quantity quantity) {}

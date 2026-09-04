package de.sgart.collaboration.domain;

/**
 * An item's in-trip lifecycle state (Story 3.3, AC1, Cl. 1; Story 3.4, Cl. 1). {@code OPEN} is the
 * birth state — folded on {@link de.sgart.collaboration.domain.event.ItemAdded}; {@code DONE} is
 * reached only by the {@code IN_TRIP}-gated {@link
 * de.sgart.collaboration.domain.event.ItemCheckedOff} and returned to {@code OPEN} by {@link
 * de.sgart.collaboration.domain.event.ItemUnchecked}. {@code DISCARDED} is the single terminal
 * "not bought, thrown away" status (Story 3.4, Cl. 1/13) — written by {@link
 * de.sgart.collaboration.domain.event.ItemDiscarded} (either the explicit {@code discardItem}
 * command or the {@code completeTrip} sweep); the item stays on the list, dimmed, distinct from a
 * removal. {@code DISCARDED} is also returned to {@code OPEN} by {@code ItemUnchecked}. Distinct
 * from {@link ListStatus} (the list's own lifecycle); the two enums are independent — a list being
 * {@code IN_TRIP} is what gates the status transitions.
 */
public enum ItemStatus {
    OPEN,
    DONE,
    DISCARDED
}

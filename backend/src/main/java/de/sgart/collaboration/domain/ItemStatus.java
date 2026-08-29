package de.sgart.collaboration.domain;

/**
 * An item's in-trip lifecycle state (Story 3.3, AC1, Cl. 1). {@code OPEN} is the birth state —
 * folded on {@link de.sgart.collaboration.domain.event.ItemAdded}; {@code DONE} and {@code
 * POSTPONED} are reached only by the {@code IN_TRIP}-gated status events ({@link
 * de.sgart.collaboration.domain.event.ItemCheckedOff}, {@link
 * de.sgart.collaboration.domain.event.ItemPostponed}) and returned to {@code OPEN} by {@link
 * de.sgart.collaboration.domain.event.ItemUnchecked}. Distinct from {@link ListStatus} (the list's
 * own lifecycle); the two enums are independent — a list being {@code IN_TRIP} is what gates the
 * status transitions. <strong>This is the only place an item reaches {@code DONE}</strong> — a
 * {@code DONE} item always has a trip context (the list was {@code IN_TRIP} when it was checked).
 */
public enum ItemStatus {
    OPEN,
    DONE,
    POSTPONED
}

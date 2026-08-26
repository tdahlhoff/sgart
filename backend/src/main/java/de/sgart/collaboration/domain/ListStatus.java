package de.sgart.collaboration.domain;

/**
 * A shopping list's lifecycle state (glossary term, Story 2.1). Only {@code OPEN} is reachable in
 * Epic 2 — {@code ShoppingListCreated} is the sole state-producing event, and it always folds to
 * {@code OPEN}. {@code IN_TRIP} (a trip starts against the list) and {@code DONE} (the trip
 * completes) are Epic 3 transitions; the vocabulary and the {@link ShoppingList#rename} invariant
 * that reads this enum exist from the aggregate's birth, but no Epic-2 command drives a list out of
 * {@code OPEN} (see Story 2.1 Clarification 1 — do not fabricate an Epic-3 transition to reach it
 * early).
 */
public enum ListStatus {
    OPEN,
    IN_TRIP,
    DONE
}

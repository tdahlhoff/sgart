package de.sgart.collaboration.domain;

/**
 * Which aggregate method raised an {@link de.sgart.collaboration.domain.event.ItemTransferInitiated}
 * (Story 3.6, decision 1) — {@code PLANNING_MOVE} from {@link ShoppingList#moveItem} (requires
 * {@code OPEN}), {@code IN_TRIP_POSTPONE} from {@link ShoppingList#postponeItemToList} (requires
 * {@code IN_TRIP}). The two phases share one saga vocabulary; this discriminator preserves the
 * move-vs-postpone distinction for audit/telemetry without duplicating the transfer machinery.
 */
public enum TransferOrigin {
    PLANNING_MOVE,
    IN_TRIP_POSTPONE
}

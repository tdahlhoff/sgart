package de.sgart.collaboration.domain;

/**
 * Why the {@code ItemTransferProcessManager} compensated a reserved transfer with {@link
 * de.sgart.collaboration.domain.event.ItemTransferCancelled} (Story 3.6, AC3) — {@code
 * TARGET_NOT_OPEN} when the target list left {@code OPEN} (e.g. a concurrent {@code StartTrip}
 * between the handler's pre-check and the async add), {@code TARGET_GONE} when the target stream
 * no longer exists. For logs/audit and future live-sync surfacing (Epic 4) — not yet shown in the
 * client (decision 3, no polling).
 */
public enum TransferCancellationReason {
    TARGET_NOT_OPEN,
    TARGET_GONE
}

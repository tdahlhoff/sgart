package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SGART's first process manager (Story 2.4, AD-10), reshaped into the Story 3.6 two-phase
 * compensating saga (renamed from {@code ItemMoveProcessManager}, decision 1). Reacts to a single
 * {@link ItemTransferInitiated} — raised by either the planning-move or the in-trip-postpone
 * aggregate method, sharing one saga vocabulary — and drives it to resolution:
 *
 * <ol>
 *   <li>Add the item to the <strong>target</strong> list.
 *   <li>On success (or a converged duplicate) — issue {@code confirmItemTransfer} on the
 *       <strong>source</strong>, removing the reservation there.
 *   <li>On failure (target not {@code OPEN}, or gone) — issue {@code cancelItemTransfer} on the
 *       <strong>source</strong>, un-reserving the item there.
 * </ol>
 *
 * <p>The item is on exactly one list at every instant (reserved-on-source, then either
 * removed-from-source or un-reserved-on-source) — never dropped on neither list, which is the bug
 * this story fixes (the interim {@code UNRECOVERABLE_TRANSFER} log-and-drop guard is gone).
 *
 * <p>Only the source append (the initiate) was authorized by a caller (the handler's membership
 * check); this component acts on the system's own behalf for every step here and does
 * <strong>no</strong> {@code ResolveMemberIdentity} call — it uses the {@link EventStore} port
 * directly, exactly like a command handler minus the identity resolution.
 *
 * <p><strong>Exactly-once (AC2):</strong> a single {@link CommandId} is derived deterministically
 * from the triggering event's id ({@link CommandId#deterministicFrom(de.sgart.shared.EventId)})
 * and reused for <em>both</em> the target add and the source confirm/cancel — per-stream {@code
 * (stream, commandId)} dedupe on the {@link EventStore} makes this safe: re-processing the same
 * {@link ItemTransferInitiated} on a subscription restart or catch-up replay derives the same id on
 * every stream it touches, so a redelivered step is a silent no-op. Never call {@link
 * CommandId#generate()} here — that would double-add/double-confirm/double-cancel on replay. The
 * only unhandled edge is a target that flaps not-open→open across a replay (pathological; would
 * double-place) — an accepted edge alongside the existing checkpoint/at-least-once debts (YAGNI,
 * {@code deferred-work.md} 3.2), not built for.
 *
 * <p><strong>Scope: the clean (non-collision) transfer only.</strong> The interactive
 * quantity-merge branch (a target already holding the same (name, note) key) is client-orchestrated
 * (Story 2.4, Clarification 3) — a process manager has no member to prompt. The {@link
 * DuplicateItemException} swallow below is only the race safety net for a stale client pre-check,
 * treated as a converged transfer (confirm on the source), not the common path.
 *
 * <p>The KurrentDB subscription that drives this class lives in {@code adapter.out} (a second,
 * independent {@code list-}-prefix subscription alongside the projector's), keeping this class a
 * pure, infra-free, {@code InMemoryEventStore}-testable application component (AD-1).
 */
public final class ItemTransferProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ItemTransferProcessManager.class);

    /**
     * Bounded retries for an append when a concurrent write advances a stream between the load and
     * the append. The conflict window is tiny (load-then-append on one stream), so a handful of
     * attempts converges in practice; exhausting them on the target add rethrows to the
     * subscription's log-and-skip (a later catch-up replay retries, idempotent), while exhausting
     * them on a source saga step logs and converges only on the next catch-up replay (mirrors
     * {@link TripLifecycleProcessManager#onTripCompletedForList}).
     */
    private static final int MAX_APPEND_ATTEMPTS = 5;

    private final EventStore eventStore;

    public ItemTransferProcessManager(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    /** Reacts to one {@link ItemTransferInitiated}, driving the saga to confirm or cancel. */
    public void onItemTransferInitiated(ItemTransferInitiated initiated) {
        Objects.requireNonNull(initiated, "initiated must not be null");

        CommandId derivedCommandId = CommandId.deterministicFrom(initiated.eventId());
        StreamId targetStreamId = StreamId.forList(initiated.targetListId());

        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            List<DomainEvent> targetHistory = eventStore.readStream(targetStreamId);
            if (targetHistory.isEmpty()) {
                // The target stream is gone — compensate instead of dropping (Story 3.6, AC3).
                cancelOnSource(initiated, derivedCommandId, TransferCancellationReason.TARGET_GONE);
                return;
            }

            ShoppingList target = ShoppingList.rehydrate(targetStreamId, targetHistory);
            AggregateVersion targetLoadedVersion = target.version();

            try {
                target.addItem(
                        initiated.itemId(), initiated.name(), initiated.note(), initiated.quantity(), derivedCommandId);
            } catch (DuplicateItemException alreadyPresent) {
                log.debug(
                        "ItemTransferProcessManager: target {} already holds item {}'s key, treating transfer as converged",
                        initiated.targetListId(), initiated.itemId());
                confirmOnSource(initiated, derivedCommandId);
                return;
            } catch (ItemChangeNotPermittedException targetNoLongerOpen) {
                // The target left OPEN (e.g. a concurrent StartTrip) between the handler's OPEN
                // check and this async add — compensate instead of dropping (Story 3.6, AC3).
                cancelOnSource(initiated, derivedCommandId, TransferCancellationReason.TARGET_NOT_OPEN);
                return;
            }

            try {
                eventStore.append(targetLoadedVersion, target.uncommittedEvents(), derivedCommandId);
                confirmOnSource(initiated, derivedCommandId);
                return;
            } catch (ConcurrencyConflictException targetAdvanced) {
                log.debug(
                        "ItemTransferProcessManager: target {} advanced during transfer of item {}, retry {}/{}",
                        initiated.targetListId(), initiated.itemId(), attempt, MAX_APPEND_ATTEMPTS);
            }
        }

        throw new IllegalStateException(
                "ItemTransferProcessManager: target " + initiated.targetListId()
                        + " kept losing the append race for item " + initiated.itemId()
                        + " after " + MAX_APPEND_ATTEMPTS + " attempts");
    }

    private void confirmOnSource(ItemTransferInitiated initiated, CommandId derivedCommandId) {
        appendSourceSagaStep(
                initiated,
                derivedCommandId,
                "confirm",
                source -> source.confirmItemTransfer(initiated.itemId(), derivedCommandId));
    }

    private void cancelOnSource(
            ItemTransferInitiated initiated, CommandId derivedCommandId, TransferCancellationReason reason) {
        appendSourceSagaStep(
                initiated,
                derivedCommandId,
                "cancel",
                source -> source.cancelItemTransfer(initiated.itemId(), reason, derivedCommandId));
    }

    /**
     * Shared read-rehydrate-append-retry against the <strong>source</strong> stream (mirrors {@link
     * TripLifecycleProcessManager#onTripCompletedForList}'s second-stream template) — applies {@code
     * sagaStep} (confirm or cancel) and appends only if it actually raised something; a convergent
     * no-op (the source already resolved this on an earlier pass) skips the append entirely.
     */
    private void appendSourceSagaStep(
            ItemTransferInitiated initiated, CommandId derivedCommandId, String stepName, Consumer<ShoppingList> sagaStep) {
        StreamId sourceStreamId = StreamId.forList(initiated.sourceListId());

        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            List<DomainEvent> sourceHistory = eventStore.readStream(sourceStreamId);
            if (sourceHistory.isEmpty()) {
                log.error(
                        "ItemTransferProcessManager: source {} has no stream while resolving the {} step for item {}"
                                + " — cannot proceed; a later catch-up replay retries",
                        initiated.sourceListId(), stepName, initiated.itemId());
                return;
            }
            ShoppingList source = ShoppingList.rehydrate(sourceStreamId, sourceHistory);
            AggregateVersion sourceLoadedVersion = source.version();
            sagaStep.accept(source);

            if (source.uncommittedEvents().isEmpty()) {
                // Convergent no-op — an earlier pass already resolved this step (idempotent replay).
                return;
            }
            try {
                eventStore.append(sourceLoadedVersion, source.uncommittedEvents(), derivedCommandId);
                return;
            } catch (ConcurrencyConflictException sourceAdvanced) {
                log.debug(
                        "ItemTransferProcessManager: source {} advanced during the {} step for item {}, retry {}/{}",
                        initiated.sourceListId(), stepName, initiated.itemId(), attempt, MAX_APPEND_ATTEMPTS);
            }
        }

        // Sustained contention exhausted the bounded retry — converges only on the next catch-up
        // replay (resubscribe/restart, idempotent via the derived command id), mirroring
        // TripLifecycleProcessManager.onTripCompletedForList.
        log.error(
                "ItemTransferProcessManager: source {} kept losing the append race for the {} step on item {}"
                        + " after {} attempts; converges on the next catch-up replay",
                initiated.sourceListId(), stepName, initiated.itemId(), MAX_APPEND_ATTEMPTS);
    }
}

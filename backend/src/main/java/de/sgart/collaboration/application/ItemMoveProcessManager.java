package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemPostponedToList;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SGART's first process manager (Story 2.4, AD-10) — reacts to {@link ItemMovedToList} (raised on
 * the <em>source</em> list by {@link de.sgart.collaboration.application.command.MoveItemHandler})
 * and adds the moved item to the <strong>target</strong> list. Only the source append was
 * authorized by a caller (the handler's membership check); this component acts on the system's own
 * behalf and does <strong>no</strong> {@code ResolveMemberIdentity} call — it uses the {@link
 * EventStore} port directly, exactly like a command handler minus the identity resolution.
 *
 * <p><strong>Exactly-once (AC2):</strong> the target {@code AddItem} command id is derived
 * deterministically from the triggering event's id ({@link CommandId#deterministicFrom(de.sgart.shared.EventId)}),
 * so re-processing the same {@code ItemMovedToList} on a subscription restart or catch-up replay
 * derives the same command id — the {@link EventStore}'s command-id idempotency collapses the
 * redelivered add to a silent no-op. Never call {@link CommandId#generate()} here — that would
 * double-add on replay.
 *
 * <p><strong>Scope: the clean (non-collision) move only.</strong> The interactive quantity-merge
 * branch (a target already holding the same (name, note) key) is client-orchestrated (Story 2.4,
 * Clarification 3) — a process manager has no member to prompt. The {@link DuplicateItemException}
 * swallow below is only the race safety net for a stale client pre-check, not the common path.
 *
 * <p>The KurrentDB subscription that drives this class lives in {@code adapter.out} (a second,
 * independent {@code list-}-prefix subscription alongside the projector's), keeping this class a
 * pure, infra-free, {@code InMemoryEventStore}-testable application component (AD-1).
 */
public final class ItemMoveProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ItemMoveProcessManager.class);

    /** Log marker for an interim data-loss condition operators/alerting should watch (story 3-6). */
    private static final String UNRECOVERABLE_TRANSFER = "UNRECOVERABLE_TRANSFER";

    /**
     * Bounded retries for the target append when a concurrent write advances the target stream
     * between the load and the append. The conflict window is tiny (load-then-append on one stream),
     * so a handful of attempts converges in practice; exhausting them rethrows to the subscription's
     * log-and-skip, where a later catch-up replay retries with the same derived id (idempotent).
     */
    private static final int MAX_APPEND_ATTEMPTS = 5;

    private final EventStore eventStore;

    public ItemMoveProcessManager(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    /** Reacts to one {@link ItemMovedToList}, adding the item to its target list exactly once. */
    public void onItemMovedToList(ItemMovedToList moved) {
        Objects.requireNonNull(moved, "moved must not be null");
        addItemToTarget(moved.targetListId(), moved.itemId(), moved.name(), moved.note(), moved.quantity(),
                CommandId.deterministicFrom(moved.eventId()), "move");
    }

    /**
     * Reacts to one {@link ItemPostponedToList} (Story 3.3, AC4/AC5), adding the postponed item to
     * its target list exactly once — identical mechanics to {@link #onItemMovedToList}, same
     * exactly-once guarantee via derived command id.
     */
    public void onItemPostponedToList(ItemPostponedToList postponed) {
        Objects.requireNonNull(postponed, "postponed must not be null");
        addItemToTarget(postponed.targetListId(), postponed.itemId(), postponed.name(), postponed.note(),
                postponed.quantity(), CommandId.deterministicFrom(postponed.eventId()), "postpone-to-list");
    }

    /**
     * Shared load-retry loop: reads the target list, calls {@code addItem}, and appends — retrying
     * on an optimistic-concurrency conflict up to {@link #MAX_APPEND_ATTEMPTS} times. The derived
     * {@code commandId} is stable across retries (never generates a new one), so the append is still
     * exactly-once even after a restart-replay.
     */
    private void addItemToTarget(
            ShoppingListId targetListId,
            ItemId itemId,
            ItemName name,
            ItemNote note,
            Quantity quantity,
            CommandId derivedCommandId,
            String operation) {
        StreamId targetStreamId = StreamId.forList(targetListId);

        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            List<DomainEvent> targetHistory = eventStore.readStream(targetStreamId);
            if (targetHistory.isEmpty()) {
                // The source already removed the item, but the target stream is gone — the item is
                // now on neither list. Interim guard (story 3-6): surface this loudly instead of a
                // quiet warn-and-drop, so it is alertable and an operator can restore it manually.
                // The auto-recovering reserve-then-remove saga is deferred to 3-6.
                log.error("{}: TRANSFER FAILED — target list {} has no stream; item {} was removed "
                        + "from source but NOT placed on target ({}). Manual recovery needed; "
                        + "auto-recovery tracked in story 3-6-two-phase-transfer-saga.",
                        UNRECOVERABLE_TRANSFER, targetListId, itemId, operation);
                return;
            }

            ShoppingList target = ShoppingList.rehydrate(targetStreamId, targetHistory);
            AggregateVersion targetLoadedVersion = target.version();

            try {
                target.addItem(itemId, name, note, quantity, derivedCommandId);
            } catch (DuplicateItemException alreadyPresent) {
                log.debug("ItemMoveProcessManager: target {} already holds item {}'s key, treating {} as converged",
                        targetListId, itemId, operation);
                return;
            } catch (ItemChangeNotPermittedException targetNoLongerOpen) {
                // The target left OPEN (e.g. a concurrent StartTrip) between the handler's OPEN check
                // and this async add. The source already removed the item, so it is now on neither
                // list. Interim guard (story 3-6): surface loudly instead of the old silent drop.
                log.error("{}: TRANSFER FAILED — target list {} is no longer Open; item {} was removed "
                        + "from source but NOT placed on target ({}). Manual recovery needed; "
                        + "auto-recovery tracked in story 3-6-two-phase-transfer-saga.",
                        UNRECOVERABLE_TRANSFER, targetListId, itemId, operation);
                return;
            }

            try {
                eventStore.append(targetLoadedVersion, target.uncommittedEvents(), derivedCommandId);
                return;
            } catch (ConcurrencyConflictException targetAdvanced) {
                log.debug("ItemMoveProcessManager: target {} advanced during {} of item {}, retry {}/{}",
                        targetListId, operation, itemId, attempt, MAX_APPEND_ATTEMPTS);
            }
        }

        throw new IllegalStateException(
                "ItemMoveProcessManager: target " + targetListId
                        + " kept losing the append race for item " + itemId
                        + " after " + MAX_APPEND_ATTEMPTS + " attempts");
    }
}

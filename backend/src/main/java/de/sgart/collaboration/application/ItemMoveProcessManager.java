package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.event.ItemMovedToList;
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

        StreamId targetStreamId = StreamId.forList(moved.targetListId());
        CommandId derivedCommandId = CommandId.deterministicFrom(moved.eventId());

        // Load-then-append on the target stream: retry a lost optimistic-concurrency race a bounded
        // number of times. Without this, a ConcurrencyConflictException would propagate to the
        // subscription's log-and-skip and the moved item would be stranded on neither list under a
        // live subscription (no re-delivery until a restart) — a silent data loss. The derived
        // command id is stable across attempts, so a retry can never double-add.
        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            List<DomainEvent> targetHistory = eventStore.readStream(targetStreamId);
            if (targetHistory.isEmpty()) {
                // The target vanished — Epic-2-unreachable (no Epic-2 event deletes a list), defensive.
                log.warn("ItemMoveProcessManager: target list {} has no stream, skipping move of item {}",
                        moved.targetListId(), moved.itemId());
                return;
            }

            ShoppingList target = ShoppingList.rehydrate(targetStreamId, targetHistory);
            AggregateVersion targetLoadedVersion = target.version();

            try {
                target.addItem(moved.itemId(), moved.name(), moved.note(), moved.quantity(), derivedCommandId);
            } catch (DuplicateItemException alreadyPresent) {
                // Race safety net (Cl. 3): a stale client pre-check let a colliding item down the clean-move
                // path. Convergent success — no duplicate is created, and the source removal already stands.
                log.debug("ItemMoveProcessManager: target {} already holds item {}'s key, treating as converged",
                        moved.targetListId(), moved.itemId());
                return;
            } catch (ItemChangeNotPermittedException targetNoLongerOpen) {
                // The target went non-Open mid-move — Epic-2-unreachable. Real compensation is Epic 3's.
                log.warn("ItemMoveProcessManager: target {} is no longer Open, dropping move of item {}",
                        moved.targetListId(), moved.itemId());
                return;
            }

            try {
                eventStore.append(targetLoadedVersion, target.uncommittedEvents(), derivedCommandId);
                return;
            } catch (ConcurrencyConflictException targetAdvanced) {
                // Another write landed on the target between our read and append; re-read and retry.
                log.debug("ItemMoveProcessManager: target {} advanced during move of item {}, retry {}/{}",
                        moved.targetListId(), moved.itemId(), attempt, MAX_APPEND_ATTEMPTS);
            }
        }

        // Exhausted the bounded retries — hand the conflict to the subscription's log-and-skip; a
        // later catch-up replay retries with the same derived id (idempotent, so still exactly-once).
        throw new IllegalStateException(
                "ItemMoveProcessManager: target " + moved.targetListId()
                        + " kept losing the append race for item " + moved.itemId()
                        + " after " + MAX_APPEND_ATTEMPTS + " attempts");
    }
}

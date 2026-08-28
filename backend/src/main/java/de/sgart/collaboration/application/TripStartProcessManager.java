package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.EventStore;
import de.sgart.shared.StreamId;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SGART's second process manager (Story 3.1, AD-10) — reacts to {@link TripStartedForList} (raised
 * on the list's own {@code list-{id}} stream by {@link
 * de.sgart.collaboration.application.command.StartTripHandler}) and creates the {@link
 * ShoppingTrip} aggregate on its own fresh {@code trip-{id}} stream (Cl. 1). This component acts on
 * the system's own behalf — the caller's membership was already checked when {@code
 * TripStartedForList} was appended — and does <strong>no</strong> {@code ResolveMemberIdentity}
 * call, exactly like {@link ItemMoveProcessManager}.
 *
 * <p><strong>Exactly-once (retro Action 6):</strong> the trip-creation command id is derived
 * deterministically from the triggering event's id ({@link
 * CommandId#deterministicFrom(de.sgart.shared.EventId)}), so re-processing the same {@code
 * TripStartedForList} on a subscription restart or catch-up replay derives the same command id.
 * Because the trip stream is <strong>new</strong>, the only possible append conflict is a
 * redelivery racing itself — the trip already exists — so a {@link ConcurrencyConflictException} on
 * the create append is caught and treated as converged (already created, exactly-once via the
 * deterministic id), logged at debug (the create-analogue of the Story 2.4 {@code
 * DuplicateItemException} swallow). Never call {@link CommandId#generate()} here — that would
 * double-create on replay.
 *
 * <p>Infra-free and {@code InMemoryEventStore}-testable, mirroring {@link ItemMoveProcessManager}.
 * There is no orphaned-trip risk: the trip is only ever created after the list transition committed
 * (Cl. 1).
 */
public final class TripStartProcessManager {

    private static final Logger log = LoggerFactory.getLogger(TripStartProcessManager.class);

    private final EventStore eventStore;

    public TripStartProcessManager(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    /** Reacts to one {@link TripStartedForList}, creating the trip exactly once. */
    public void onTripStartedForList(TripStartedForList started) {
        Objects.requireNonNull(started, "started must not be null");

        CommandId derivedCommandId = CommandId.deterministicFrom(started.eventId());
        ShoppingTrip trip = ShoppingTrip.start(
                started.tripId(),
                started.householdId(),
                started.listId(),
                started.storeIds(),
                derivedCommandId);

        try {
            eventStore.append(
                    AggregateVersion.initial(StreamId.forTrip(started.tripId())),
                    trip.uncommittedEvents(),
                    derivedCommandId);
        } catch (ConcurrencyConflictException alreadyCreated) {
            // Redelivery of the same trigger — the trip was already created on an earlier pass
            // (exactly-once via the deterministic command id). Convergent success, not an error.
            log.debug(
                    "TripStartProcessManager: trip {} already created, treating as converged",
                    started.tripId());
        }
    }
}

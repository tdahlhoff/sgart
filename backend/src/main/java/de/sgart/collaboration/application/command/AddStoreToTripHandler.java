package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.TripNotActiveApplicationException;
import de.sgart.collaboration.application.exception.TripNotFoundException;
import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.exception.TripNotActiveException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@link AddStoreToTrip} (AC3, AC5): resolve the caller's household-scoped {@code
 * MemberId} (AD-2), load the {@link ShoppingTrip} aggregate (mirrors {@link StartTripHandler}'s
 * load-then-append, but loading the trip rather than the list), and let it enforce the {@code
 * ACTIVE}-only and already-in-trip-no-op invariants. The append uses the <em>loaded</em> stream
 * version as the expected version (AD-8); an already-in-trip no-op raises nothing, so the append
 * is skipped.
 */
public final class AddStoreToTripHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public AddStoreToTripHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws TripNotFoundException if {@code tripId} is unknown or belongs to another household (404)
     * @throws TripNotActiveApplicationException if the trip is not {@code ACTIVE} (409, defensive)
     */
    public void handle(
            String keycloakUserId, String rawHouseholdId, String rawTripId, String rawStoreId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        TripId tripId = CommandFieldTranslations.toTripId(rawTripId);
        StoreId storeId = CommandFieldTranslations.toStoreId(rawStoreId);

        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingTrip trip = loadTripOwnedBy(householdId, tripId);
        AggregateVersion loadedVersion = trip.version();
        AddStoreToTrip command = new AddStoreToTrip(tripId, storeId, commandId, loadedVersion);

        try {
            trip.addStore(command.storeId(), command.commandId());
        } catch (TripNotActiveException notActive) {
            throw new TripNotActiveApplicationException(notActive.getMessage());
        }

        if (!trip.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), trip.uncommittedEvents(), command.commandId());
        }
    }

    private ShoppingTrip loadTripOwnedBy(HouseholdId householdId, TripId tripId) {
        StreamId streamId = StreamId.forTrip(tripId);
        List<DomainEvent> history = eventStore.readStream(streamId);
        if (history.isEmpty()) {
            throw new TripNotFoundException("No trip found for id " + tripId);
        }
        ShoppingTrip trip = ShoppingTrip.rehydrate(streamId, history);
        if (!trip.householdId().equals(householdId)) {
            throw new TripNotFoundException("No trip found for id " + tripId);
        }
        return trip;
    }
}

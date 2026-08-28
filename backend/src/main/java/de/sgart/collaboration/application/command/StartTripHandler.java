package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidTripStoreSelectionException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.application.exception.TripNotStartableApplicationException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.exception.TripNotStartableException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@link StartTrip} (AC1, AC2, AC3, AC7, Cl. 1) — the single guarded append that
 * moves the list {@code Open → In-Trip}. Loads only the list (never a {@code ShoppingTrip} — it
 * does not exist yet); the {@code TripStartProcessManager} reacts to the raised {@code
 * TripStartedForList} to create it (AD-10, Cl. 1). The append uses the <em>loaded</em> stream
 * version as the expected version (online load-then-append, AD-8); a concurrent write loses with
 * the store's {@code ConcurrencyConflictException} (→ 409) — which is also how "at most one Active
 * trip per list" holds under a race. Mirrors {@link MoveItemHandler}.
 */
public final class StartTripHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public StartTripHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidTripStoreSelectionException if {@code rawStoreIds} is empty (400, AC3)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if the list is unknown or belongs to another household (404)
     * @throws TripNotStartableApplicationException if the list is not {@code Open} (409, AC2)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawListId,
            String rawTripId,
            List<String> rawStoreIds,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);
        TripId tripId = CommandFieldTranslations.toTripId(rawTripId);
        List<StoreId> storeIds = CommandFieldTranslations.toStoreIdList(rawStoreIds);

        if (storeIds.isEmpty()) {
            throw new InvalidTripStoreSelectionException("A trip requires at least one store");
        }

        // A non-member never reaches startTrip — NotAMemberException propagates as a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingList list = loadListOwnedBy(householdId, listId);
        AggregateVersion loadedVersion = list.version();
        StartTrip command = new StartTrip(listId, tripId, storeIds, commandId, loadedVersion);

        try {
            list.startTrip(command.tripId(), command.storeIds(), command.commandId());
        } catch (TripNotStartableException notStartable) {
            throw new TripNotStartableApplicationException(notStartable.getMessage());
        }

        eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
    }

    private ShoppingList loadListOwnedBy(HouseholdId householdId, ShoppingListId listId) {
        StreamId streamId = StreamId.forList(listId);
        List<DomainEvent> history = eventStore.readStream(streamId);
        if (history.isEmpty()) {
            throw new ShoppingListNotFoundException("No shopping list found for id " + listId);
        }
        ShoppingList list = ShoppingList.rehydrate(streamId, history);
        if (!list.householdId().equals(householdId)) {
            throw new ShoppingListNotFoundException("No shopping list found for id " + listId);
        }
        return list;
    }
}

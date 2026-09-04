package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.application.exception.TripNotCompletableApplicationException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.exception.TripNotCompletableException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@link CompleteTrip} (Story 3.4, AC4, Cl. 7): resolve the caller's
 * household-scoped {@code MemberId} (AD-2), load the {@link ShoppingList} aggregate, and let it
 * enforce the {@code IN_TRIP}-only invariant. Completion is a {@code ShoppingList} command — the
 * {@code tripId} path parameter is validated for completeness and REST clarity but the command
 * itself resolves via the list (Cl. 7). The {@link
 * de.sgart.collaboration.application.TripLifecycleProcessManager} reacts to the raised {@code
 * TripCompletedForList} to complete the trip aggregate (AD-10). Mirrors {@link StartTripHandler}.
 */
public final class CompleteTripHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public CompleteTripHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if the list is unknown or belongs to another household (404)
     * @throws TripNotCompletableApplicationException if the list is not {@code IN_TRIP} (409)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawListId,
            String rawTripId,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);
        TripId tripId = CommandFieldTranslations.toTripId(rawTripId);

        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingList list = loadListOwnedBy(householdId, listId);
        AggregateVersion loadedVersion = list.version();
        CompleteTrip command = new CompleteTrip(listId, tripId, commandId, loadedVersion);

        try {
            list.completeTrip(command.commandId());
        } catch (TripNotCompletableException notCompletable) {
            throw new TripNotCompletableApplicationException(notCompletable.getMessage());
        }

        if (!list.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
        }
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

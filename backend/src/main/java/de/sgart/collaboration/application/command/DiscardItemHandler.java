package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.collaboration.domain.exception.ItemNotDuringTripException;
import de.sgart.collaboration.domain.exception.ItemTransferInProgressException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@link DiscardItem} (Story 3.4, AC2, Cl. 12): resolve the caller's
 * household-scoped {@code MemberId} (AD-2), load the {@link ShoppingList} aggregate, and let it
 * enforce the {@code IN_TRIP}-only and unknown-item invariants. A convergent no-op (already
 * DISCARDED) raises nothing; the append is skipped in that case. Mirrors {@link CheckOffItemHandler}.
 */
public final class DiscardItemHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public DiscardItemHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if {@code listId} is unknown or belongs to another household (404)
     * @throws ItemNotFoundApplicationException if {@code itemId} is unknown on the list (404)
     * @throws ItemNotDuringTripApplicationException if the list is not {@code IN_TRIP} (409)
     * @throws ItemTransferInProgressApplicationException if the item is reserved by a pending
     *     transfer (409, Story 3.6, AC4)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawListId,
            String rawItemId,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);
        ItemId itemId = CommandFieldTranslations.toItemId(rawItemId);

        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forList(listId);
        List<DomainEvent> history = eventStore.readStream(streamId);
        if (history.isEmpty()) {
            throw new ShoppingListNotFoundException("No shopping list found for id " + listId);
        }
        ShoppingList list = ShoppingList.rehydrate(streamId, history);
        if (!list.householdId().equals(householdId)) {
            throw new ShoppingListNotFoundException("No shopping list found for id " + listId);
        }
        AggregateVersion loadedVersion = list.version();
        DiscardItem command = new DiscardItem(listId, itemId, commandId, loadedVersion);

        try {
            list.discardItem(command.itemId(), command.commandId());
        } catch (ItemNotFoundException notFound) {
            throw new ItemNotFoundApplicationException(notFound.getMessage());
        } catch (ItemNotDuringTripException notDuringTrip) {
            throw new ItemNotDuringTripApplicationException(notDuringTrip.getMessage());
        } catch (ItemTransferInProgressException inProgress) {
            throw new ItemTransferInProgressApplicationException(inProgress.getMessage());
        }

        if (!list.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
        }
    }
}

package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidMoveTargetException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.MoveTargetNotOpenException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ListStatus;
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
 * Orchestrates {@link PostponeItemToList} (Story 3.3, AC4/AC5) — mirrors {@link MoveItemHandler}
 * but for the {@code IN_TRIP} phase. Loads both the source ({@code IN_TRIP}) and target
 * ({@code OPEN}) aggregates, mutates only the source; the {@code ItemTransferProcessManager} reacts to
 * the raised {@link de.sgart.collaboration.domain.event.ItemTransferInitiated} and issues an
 * {@code AddItem} on the target (AD-10, single writer per append). The target list must be OPEN;
 * a new list is created by the client before calling this endpoint (AC5).
 */
public final class PostponeItemToListHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public PostponeItemToListHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidMoveTargetException if {@code rawTargetListId} equals {@code rawSourceListId} (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if the source or target list is unknown or belongs to
     *     another household (404)
     * @throws ItemNotFoundApplicationException if {@code rawItemId} is unknown on the source (404)
     * @throws MoveTargetNotOpenException if the target list is not {@code Open} (409)
     * @throws ItemNotDuringTripApplicationException if the source list is not {@code IN_TRIP} (409)
     * @throws ItemTransferInProgressApplicationException if the item is reserved by a different
     *     in-flight transfer (409, Story 3.6, AC4)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawSourceListId,
            String rawItemId,
            String rawTargetListId,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId sourceListId = CommandFieldTranslations.toShoppingListId(rawSourceListId);
        ItemId itemId = CommandFieldTranslations.toItemId(rawItemId);
        ShoppingListId targetListId = CommandFieldTranslations.toShoppingListId(rawTargetListId);

        if (targetListId.equals(sourceListId)) {
            throw new InvalidMoveTargetException("A postpone-to-list target must differ from its source list");
        }

        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingList source = loadListOwnedBy(householdId, sourceListId);
        ShoppingList target = loadListOwnedBy(householdId, targetListId);
        if (target.status() != ListStatus.OPEN) {
            throw new MoveTargetNotOpenException("Postpone target list " + targetListId + " is not Open");
        }

        AggregateVersion sourceLoadedVersion = source.version();
        PostponeItemToList command = new PostponeItemToList(sourceListId, itemId, targetListId, commandId, sourceLoadedVersion);

        try {
            source.postponeItemToList(command.itemId(), command.targetListId(), command.commandId());
        } catch (ItemNotFoundException notFound) {
            throw new ItemNotFoundApplicationException(notFound.getMessage());
        } catch (ItemNotDuringTripException notDuringTrip) {
            throw new ItemNotDuringTripApplicationException(notDuringTrip.getMessage());
        } catch (ItemTransferInProgressException inProgress) {
            throw new ItemTransferInProgressApplicationException(inProgress.getMessage());
        }

        // A retry of the same in-flight transfer raises nothing (convergent no-op, Story 3.6,
        // AC4) — skip the append so it does not record a spurious command id.
        if (!source.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), source.uncommittedEvents(), command.commandId());
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

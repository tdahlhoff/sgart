package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidMoveTargetException;
import de.sgart.collaboration.application.exception.ItemChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.MoveTargetNotOpenException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
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
 * Orchestrates {@link MoveItem} (AC1, AC5, AC8) — the source side of SGART's first cross-aggregate
 * effect (AD-10). Loads <strong>both</strong> the source and target {@link ShoppingList} aggregates
 * to enforce the move's invariants, then mutates only the source: the target is added to by the
 * {@code ItemTransferProcessManager} reacting to the raised {@link
 * de.sgart.collaboration.domain.event.ItemTransferInitiated}, never by this handler (single writer per
 * append). The append uses the <em>loaded source</em> stream version as the expected version
 * (online load-then-append, AD-8); a concurrent write loses with the store's {@code
 * ConcurrencyConflictException} (→ 409). Mirrors {@link AddItemHandler}.
 */
public final class MoveItemHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public MoveItemHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidMoveTargetException if {@code rawTargetListId} equals {@code rawSourceListId} (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if the source or target list is unknown or belongs to
     *     another household (404)
     * @throws MoveTargetNotOpenException if the target list is not {@code Open} (409)
     * @throws ItemChangeNotPermittedApplicationException if the source list is not {@code Open} (403)
     * @throws ItemNotFoundApplicationException if {@code rawItemId} is unknown on the source (404)
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
            throw new InvalidMoveTargetException("A move's target list must differ from its source list");
        }

        // A non-member never reaches moveItem — NotAMemberException propagates as a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingList source = loadListOwnedBy(householdId, sourceListId);
        ShoppingList target = loadListOwnedBy(householdId, targetListId);
        if (target.status() != ListStatus.OPEN) {
            throw new MoveTargetNotOpenException("Move target list " + targetListId + " is not Open");
        }

        AggregateVersion sourceLoadedVersion = source.version();
        MoveItem command = new MoveItem(sourceListId, itemId, targetListId, commandId, sourceLoadedVersion);

        try {
            source.moveItem(command.itemId(), command.targetListId(), command.commandId());
        } catch (ItemChangeNotPermittedException notPermitted) {
            throw new ItemChangeNotPermittedApplicationException(notPermitted.getMessage());
        } catch (ItemNotFoundException notFound) {
            throw new ItemNotFoundApplicationException(notFound.getMessage());
        } catch (ItemTransferInProgressException inProgress) {
            throw new ItemTransferInProgressApplicationException(inProgress.getMessage());
        }

        // A retry of the same in-flight transfer raises nothing (convergent no-op, Story 3.6,
        // AC4) — skip the append so it does not record a spurious command id (RenameShoppingListHandler
        // is the template).
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

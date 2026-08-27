package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
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
 * Orchestrates {@link RemoveItem} (AC4, AC5, AC8): resolve the caller's household-scoped {@code
 * MemberId} (AD-2), load the {@link ShoppingList} aggregate, and let it enforce the {@code
 * Open}-only invariant. Removing an unknown/already-removed item raises nothing, so the append is
 * skipped entirely (convergent no-op, AD-8), mirroring {@link ArchiveStoreHandler}.
 */
public final class RemoveItemHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public RemoveItemHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if {@code listId} is unknown or belongs to another household (404)
     * @throws ItemChangeNotPermittedApplicationException if the list is not {@code Open} (403)
     */
    public void handle(
            String keycloakUserId, String rawHouseholdId, String rawListId, String rawItemId, String rawCommandId) {
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
        RemoveItem command = new RemoveItem(listId, itemId, commandId, loadedVersion);

        try {
            list.removeItem(command.itemId(), command.commandId());
        } catch (ItemChangeNotPermittedException notPermitted) {
            throw new ItemChangeNotPermittedApplicationException(notPermitted.getMessage());
        }

        if (!list.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
        }
    }
}

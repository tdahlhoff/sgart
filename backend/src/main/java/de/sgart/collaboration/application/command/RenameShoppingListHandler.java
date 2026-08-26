package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.application.exception.ListNameChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.exception.ListNameChangeNotPermittedException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates {@link RenameShoppingList} (AC3): resolve the caller's household-scoped {@code
 * MemberId} through the Identity ACL's published {@link ResolveMemberIdentity} port (AD-2) to
 * confirm membership, load the {@link ShoppingList} aggregate, and let it enforce the {@code OPEN}-
 * only rename invariant (AC3). The append uses the <em>loaded</em> stream version as the expected
 * version (online load-then-append, AD-8); a concurrent write loses with the store's {@code
 * ConcurrencyConflictException} (→ 409). A no-change rename raises nothing, so the append is
 * skipped (convergent no-op, AD-8). The command returns {@code void} — a command yields no domain
 * data (CQRS); the client already knows the id and the name it sent.
 *
 * <p>An unknown {@code listId} (empty stream) and a {@code listId} whose loaded {@code householdId}
 * does not match the request path are both rejected as {@link ShoppingListNotFoundException} — a
 * list under a different household is treated the same as an unknown one, defense-in-depth against
 * a mismatched path (never a 500, never a silent write to the wrong household's list).
 */
public final class RenameShoppingListHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public RenameShoppingListHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub} by
     *     {@code adapter.in} — never accepted from the request body (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidShoppingListNameException if {@code rawName} fails the domain invariant (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws ShoppingListNotFoundException if {@code listId} is unknown or belongs to another household (404)
     * @throws ListNameChangeNotPermittedApplicationException if the list is not {@code OPEN} (403)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawListId,
            String rawName,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);
        ShoppingListName newName = CommandFieldTranslations.toShoppingListName(rawName);

        // A non-member never reaches rename — NotAMemberException propagates as a 403 (AD-2/AD-5).
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
        RenameShoppingList command = new RenameShoppingList(householdId, listId, newName, commandId, loadedVersion);

        try {
            list.rename(command.newName(), command.commandId());
        } catch (ListNameChangeNotPermittedException notPermitted) {
            throw new ListNameChangeNotPermittedApplicationException(notPermitted.getMessage());
        }

        if (!list.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
        }
    }
}

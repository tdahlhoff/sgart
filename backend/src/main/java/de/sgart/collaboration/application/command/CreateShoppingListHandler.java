package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link CreateShoppingList} (AC1): resolve the caller's household-scoped {@code
 * MemberId} through the Identity ACL's published {@link ResolveMemberIdentity} port (AD-2 — never
 * {@code identity.domain}) to confirm membership, then let the {@link ShoppingList} aggregate raise
 * {@code ShoppingListCreated} on its own brand-new {@code list-{id}} stream. The {@code listId} is
 * minted client-side and carried in the command, so the response needs no body (read-your-writes
 * without waiting on a projection). The command returns {@code void} — a command yields no domain
 * data (CQRS); the client already knows the id and name it sent.
 */
public final class CreateShoppingListHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public CreateShoppingListHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub} by
     *     {@code adapter.in} — never accepted from the request body (AR10, AD-5).
     * @param rawName the caller-supplied, optional list name; blank/absent creates a valid unnamed
     *     list (AC1/AC2), never an error.
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidShoppingListNameException if a present {@code rawName} is over-long (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
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
        ShoppingListName name = CommandFieldTranslations.toShoppingListNameOrNull(rawName);

        // A non-member never reaches create — NotAMemberException propagates as a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        AggregateVersion basedOnVersion = AggregateVersion.initial(StreamId.forList(listId));
        CreateShoppingList command = new CreateShoppingList(householdId, listId, name, commandId, basedOnVersion);

        ShoppingList list = ShoppingList.create(
                command.listId(), command.householdId(), command.name(), command.commandId());
        eventStore.append(command.basedOnVersion(), list.uncommittedEvents(), command.commandId());
    }
}

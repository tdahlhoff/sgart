package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to create a shopping list, named or unnamed, in a household (Story 2.1,
 * AC1). {@code basedOnVersion} is always {@link AggregateVersion#initial} of the freshly generated
 * list's own stream — there is no prior version to build against for a brand-new aggregate (AD-8,
 * AR10). {@code listId} is minted client-side, so the response needs no body (read-your-writes).
 */
public record CreateShoppingList(
        HouseholdId householdId,
        ShoppingListId listId,
        ShoppingListName name,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public CreateShoppingList {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        if (!basedOnVersion.isInitial()) {
            throw new IllegalArgumentException(
                    "CreateShoppingList.basedOnVersion must be the initial version of a brand-new stream");
        }
        // name is intentionally nullable — an unnamed list is valid (AC1/AC2).
    }
}

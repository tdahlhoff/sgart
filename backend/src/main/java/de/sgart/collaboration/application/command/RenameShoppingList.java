package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to rename an existing shopping list (Story 2.1, AC3). Like {@link
 * RenameHousehold}, {@code basedOnVersion} is the <em>loaded</em> stream version the handler read
 * before appending — an online load-then-append rename; a client-supplied {@code basedOnVersion}
 * plus the offline queue is Epic 5. A concurrent rename that advanced the stream past this version
 * loses with the store's {@code ConcurrencyConflictException} (AD-8).
 */
public record RenameShoppingList(
        HouseholdId householdId,
        ShoppingListId listId,
        ShoppingListName newName,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public RenameShoppingList {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}

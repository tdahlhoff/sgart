package de.sgart.collaboration.application;

import de.sgart.collaboration.application.command.CreateHouseholdHandler;
import de.sgart.collaboration.application.command.RenameHouseholdHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidHouseholdNameException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.application.exception.InvalidStoreNameException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;

/**
 * Shared fail-fast translators from raw request strings to validated command-envelope/domain
 * values (DRY — {@link CreateHouseholdHandler} and {@link RenameHouseholdHandler} both need the
 * identical mapping). Turns a malformed value into a client-localizable {@code 400} carrying a
 * stable {@code code}, so {@code adapter.in} never constructs the domain {@link HouseholdName} nor
 * parses a raw {@link CommandId} itself (layering, AR10), and a bad value never surfaces as an
 * opaque {@code 500}.
 */
public final class CommandFieldTranslations {

    private CommandFieldTranslations() {}

    public static HouseholdId toHouseholdId(String rawHouseholdId) {
        if (rawHouseholdId == null || rawHouseholdId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.householdIdRequired", "householdId must be provided");
        }
        try {
            return HouseholdId.fromString(rawHouseholdId);
        } catch (IllegalArgumentException notAUuid) {
            // The id comes straight from the request path — a malformed value is a fail-fast 400, not
            // an opaque 500 from the raw UUID parse.
            throw new InvalidCommandEnvelopeException("command.householdIdInvalid", "householdId must be a valid UUID");
        }
    }

    public static CommandId toCommandId(String rawCommandId) {
        if (rawCommandId == null || rawCommandId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.commandIdRequired", "commandId must be provided");
        }
        try {
            return CommandId.fromString(rawCommandId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.commandIdInvalid", "commandId must be a valid UUID");
        }
    }

    public static HouseholdName toHouseholdName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidHouseholdNameException("household.nameRequired", "Household name must not be blank");
        }
        try {
            return new HouseholdName(rawName);
        } catch (IllegalArgumentException tooLong) {
            // Blank is already excluded above, so the only remaining domain invariant is the length
            // bound — report it with its own code so the client shows "name too long", not "required".
            throw new InvalidHouseholdNameException("household.nameTooLong", tooLong.getMessage());
        }
    }

    public static StoreName toStoreName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidStoreNameException("store.nameRequired", "Store name must not be blank");
        }
        try {
            return new StoreName(rawName);
        } catch (IllegalArgumentException tooLong) {
            throw new InvalidStoreNameException("store.nameTooLong", tooLong.getMessage());
        }
    }

    public static StoreId toStoreId(String rawStoreId) {
        if (rawStoreId == null || rawStoreId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.storeIdRequired", "storeId must be provided");
        }
        try {
            return StoreId.fromString(rawStoreId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.storeIdInvalid", "storeId must be a valid UUID");
        }
    }

    /**
     * Translates the <em>optional</em> accepted chain id (AC2). A missing/blank value means the
     * store is unlinked and yields {@code null} — not an error; only a present-but-malformed value
     * is a fail-fast {@code 400}. The value is never validated against the reference table
     * (advisory / client-decided, AD-2).
     */
    public static StoreChainId toStoreChainIdOrNull(String rawChainId) {
        if (rawChainId == null || rawChainId.isBlank()) {
            return null;
        }
        try {
            return StoreChainId.fromString(rawChainId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.chainIdInvalid", "chainId must be a valid UUID");
        }
    }

    public static ShoppingListId toShoppingListId(String rawListId) {
        if (rawListId == null || rawListId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.listIdRequired", "listId must be provided");
        }
        try {
            return ShoppingListId.fromString(rawListId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.listIdInvalid", "listId must be a valid UUID");
        }
    }

    /**
     * Translates the <em>optional</em> create-time list name (AC1). A missing/blank value means the
     * list is unnamed and yields {@code null} — not an error (the "Liste N" case, AC2); only an
     * over-long value is a fail-fast {@code 400}.
     */
    public static ShoppingListName toShoppingListNameOrNull(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        try {
            return new ShoppingListName(rawName);
        } catch (IllegalArgumentException tooLong) {
            throw new InvalidShoppingListNameException("list.nameTooLong", tooLong.getMessage());
        }
    }

    /** Translates the <em>required</em> rename-time list name (AC3) — blank is a fail-fast {@code 400}. */
    public static ShoppingListName toShoppingListName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidShoppingListNameException("list.nameRequired", "Shopping list name must not be blank");
        }
        try {
            return new ShoppingListName(rawName);
        } catch (IllegalArgumentException tooLong) {
            throw new InvalidShoppingListNameException("list.nameTooLong", tooLong.getMessage());
        }
    }
}

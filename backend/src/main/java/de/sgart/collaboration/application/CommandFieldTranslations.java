package de.sgart.collaboration.application;

import de.sgart.collaboration.application.command.CreateHouseholdHandler;
import de.sgart.collaboration.application.command.RenameHouseholdHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidHouseholdNameException;
import de.sgart.collaboration.application.exception.InvalidItemNameException;
import de.sgart.collaboration.application.exception.InvalidItemNoteException;
import de.sgart.collaboration.application.exception.InvalidItemQuantityException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.application.exception.InvalidStoreNameException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

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

    /** Translates each raw store id in a start-trip selection (Story 3.1, AC1/AC3); does not
     * enforce non-emptiness — that is the handler's job with its own {@code
     * InvalidTripStoreSelectionException} (AC3), a distinct client-facing code from a malformed id.
     */
    public static List<StoreId> toStoreIdList(List<String> rawStoreIds) {
        if (rawStoreIds == null) {
            throw new InvalidCommandEnvelopeException("command.storeIdsRequired", "storeIds must be provided");
        }
        return rawStoreIds.stream().map(CommandFieldTranslations::toStoreId).toList();
    }

    public static TripId toTripId(String rawTripId) {
        if (rawTripId == null || rawTripId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.tripIdRequired", "tripId must be provided");
        }
        try {
            return TripId.fromString(rawTripId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.tripIdInvalid", "tripId must be a valid UUID");
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

    /**
     * Validates the list-overview {@code ?filter} query parameter (Story 2.2, AC1/AC2). Only {@code
     * open} and {@code done} are recognized; anything else is a fail-fast {@code 400} — a
     * request-envelope error like {@link #toShoppingListId}, not a domain error (the caller sent a
     * malformed request, not an invalid list). The caller dispatches on the validated value itself
     * ({@code ListOpenLists} vs. {@code ListDoneLists}); this only guards the input.
     */
    public static String toValidatedListFilter(String rawFilter) {
        if (!"open".equals(rawFilter) && !"done".equals(rawFilter)) {
            throw new InvalidCommandEnvelopeException("command.listFilterInvalid", "filter must be 'open' or 'done'");
        }
        return rawFilter;
    }

    public static ItemId toItemId(String rawItemId) {
        if (rawItemId == null || rawItemId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.itemIdRequired", "itemId must be provided");
        }
        try {
            return ItemId.fromString(rawItemId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.itemIdInvalid", "itemId must be a valid UUID");
        }
    }

    /** Translates the <em>required</em> item name (AC1) — blank is a fail-fast {@code 400}. */
    public static ItemName toItemName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidItemNameException("item.nameRequired", "Item name must not be blank");
        }
        try {
            return new ItemName(rawName);
        } catch (IllegalArgumentException tooLong) {
            throw new InvalidItemNameException("item.nameTooLong", tooLong.getMessage());
        }
    }

    /**
     * Translates the <em>optional</em> item note (AC1). A missing/blank value means the item
     * carries no note and yields {@code null} — not an error; only an over-long value is a
     * fail-fast {@code 400}.
     */
    public static ItemNote toItemNoteOrNull(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return null;
        }
        try {
            return new ItemNote(rawNote);
        } catch (IllegalArgumentException tooLong) {
            throw new InvalidItemNoteException("item.noteTooLong", tooLong.getMessage());
        }
    }

    /**
     * Translates the <em>required</em> item quantity (AC1, AD-9): a positive {@link BigDecimal}
     * amount paired with a {@link Unit} from the controlled vocabulary. A missing/malformed amount,
     * a non-positive amount, or an unrecognized unit is a fail-fast {@code 400} — never persisted.
     */
    public static Quantity toQuantity(String rawAmount, String rawUnit) {
        if (rawAmount == null || rawAmount.isBlank() || rawUnit == null || rawUnit.isBlank()) {
            throw new InvalidItemQuantityException("item.quantityRequired", "quantity amount and unit must be provided");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount);
        } catch (NumberFormatException notANumber) {
            throw new InvalidItemQuantityException("item.quantityInvalid", "amount must be a valid decimal number");
        }
        Unit unit;
        try {
            unit = Unit.valueOf(rawUnit);
        } catch (IllegalArgumentException notAUnit) {
            throw new InvalidItemQuantityException("item.quantityInvalid", "unit must be one of " + Arrays.toString(Unit.values()));
        }
        try {
            return new Quantity(amount, unit);
        } catch (IllegalArgumentException notPositive) {
            throw new InvalidItemQuantityException("item.quantityInvalid", notPositive.getMessage());
        }
    }
}

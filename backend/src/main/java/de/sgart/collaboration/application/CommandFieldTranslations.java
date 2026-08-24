package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;

/**
 * Shared fail-fast translators from raw request strings to validated command-envelope/domain
 * values (DRY — {@link CreateHouseholdHandler} and {@link RenameHouseholdHandler} both need the
 * identical mapping). Turns a malformed value into a client-localizable {@code 400} carrying a
 * stable {@code code}, so {@code adapter.in} never constructs the domain {@link HouseholdName} nor
 * parses a raw {@link CommandId} itself (layering, AR10), and a bad value never surfaces as an
 * opaque {@code 500}.
 */
final class CommandFieldTranslations {

    private CommandFieldTranslations() {}

    static HouseholdId toHouseholdId(String rawHouseholdId) {
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

    static CommandId toCommandId(String rawCommandId) {
        if (rawCommandId == null || rawCommandId.isBlank()) {
            throw new InvalidCommandEnvelopeException("command.commandIdRequired", "commandId must be provided");
        }
        try {
            return CommandId.fromString(rawCommandId);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidCommandEnvelopeException("command.commandIdInvalid", "commandId must be a valid UUID");
        }
    }

    static HouseholdName toHouseholdName(String rawName) {
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
}

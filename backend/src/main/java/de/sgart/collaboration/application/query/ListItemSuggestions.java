package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionReadModel;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of item autocomplete (Story 2.5, AC1/AC7): a household's own past item names, for
 * the list-detail fast-add field's client-side suggestion cache. A pure query — no side effects
 * (CLAUDE.md §6) — composing the Identity ACL's published {@link ResolveMemberIdentity} port
 * (AD-2, confirming the caller is a member) with the suggestion read model (AD-4). Household-scoped
 * (not list-scoped) — the history spans the whole household, mirroring {@link ListItems} but
 * without a {@code listId}.
 */
public final class ListItemSuggestions {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final ItemSuggestionReadModel itemSuggestionReadModel;

    public ListItemSuggestions(
            ResolveMemberIdentity resolveMemberIdentity, ItemSuggestionReadModel itemSuggestionReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.itemSuggestionReadModel =
                Objects.requireNonNull(itemSuggestionReadModel, "itemSuggestionReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<ItemSuggestionSummary> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may list the household's suggestions — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return itemSuggestionReadModel.suggestionsOf(householdId).stream()
                .map(ListItemSuggestions::toSummary)
                .toList();
    }

    private static ItemSuggestionSummary toSummary(ItemSuggestionView suggestion) {
        ItemNote note = suggestion.note();
        return new ItemSuggestionSummary(
                suggestion.name().value(),
                note == null ? null : note.value(),
                suggestion.quantity().amount().toPlainString(),
                suggestion.quantity().unit().name());
    }

    /**
     * A suggestion as seen by the caller: name + optional note + last-used quantity — the shape the
     * fast-add field needs. Plain {@code String}s, not domain types, so {@code adapter.in} can
     * consume this record without reaching into {@code collaboration.domain} (mirrors {@link
     * ListItems.ItemSummary}). {@code note} is {@code null} when absent; {@code amount} is a decimal
     * string, {@code unit} the enum name.
     */
    public record ItemSuggestionSummary(String name, String note, String amount, String unit) {}
}

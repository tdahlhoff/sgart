package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.readmodel.StoreReadModel;
import de.sgart.collaboration.domain.readmodel.StoreView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreChainId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of store management (AC5-structural, AC4): the household's active stores. A pure
 * query — no side effects (CLAUDE.md §6 CQRS coverage) — composing the Identity ACL's published
 * {@link ResolveMemberIdentity} port (AD-2, confirming the caller is a member) with the store read
 * model (AD-4, active stores only). This is the single active-store source the manage screen and
 * every future picker read, which is what makes AC5 structural: an archived store is never offered.
 */
public final class ListStores {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final StoreReadModel storeReadModel;

    public ListStores(ResolveMemberIdentity resolveMemberIdentity, StoreReadModel storeReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.storeReadModel = Objects.requireNonNull(storeReadModel, "storeReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<StoreSummary> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may list a household's stores — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return storeReadModel.activeStoresOf(householdId).stream()
                .map(ListStores::toSummary)
                .toList();
    }

    private static StoreSummary toSummary(StoreView store) {
        StoreChainId chainId = store.chainId();
        return new StoreSummary(
                store.storeId().toString(), store.name().value(), chainId == null ? null : chainId.toString());
    }

    /**
     * A store as seen by the caller: id + name + optional chain id — the shape the manage screen
     * (and every future picker) needs. Plain {@code String}s, not domain types, so {@code
     * adapter.in} can consume this record without reaching into {@code collaboration.domain}.
     * {@code chainId} is {@code null} for an unlinked store.
     */
    public record StoreSummary(String storeId, String name, String chainId) {}
}

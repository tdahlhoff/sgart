package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.query.ListStores.StoreSummary;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListStores;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.readmodel.StoreReadModel;
import de.sgart.collaboration.domain.readmodel.StoreView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the store query (AC4,
 * AC5-structural): it returns exactly what the active-only read model yields (mapping domain types
 * to plain strings, chain id nullable), rejects a non-member (403), and is side-effect free.
 */
class ListStoresTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListStores listStoresReading(StoreReadModel storeReadModel) {
        return new ListStores(new ResolveMemberIdentity(mappingRepository), storeReadModel);
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void forHousehold_returnsTheActiveStoresTheReadModelYields() {
        seedMembership();
        StoreId edekaId = StoreId.generate();
        StoreChainId edekaChain = StoreChainId.generate();
        StoreId marketId = StoreId.generate();
        ListStores listStores = listStoresReading(id -> List.of(
                new StoreView(edekaId, new StoreName("Edeka"), edekaChain),
                new StoreView(marketId, new StoreName("Wochenmarkt"), null)));

        List<StoreSummary> summaries = listStores.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries)
                .containsExactlyInAnyOrder(
                        new StoreSummary(edekaId.toString(), "Edeka", edekaChain.toString()),
                        new StoreSummary(marketId.toString(), "Wochenmarkt", null));
    }

    @Test
    void forHousehold_returnsEmptyWhenTheHouseholdHasNoActiveStores() {
        seedMembership();
        ListStores listStores = listStoresReading(id -> List.of());

        assertThat(listStores.forHousehold(MEMBER_SUB, householdId.toString())).isEmpty();
    }

    @Test
    void forHousehold_rejectsANonMemberWith403() {
        // No membership seeded for this caller.
        ListStores listStores = listStoresReading(id -> List.of());

        assertThatThrownBy(() -> listStores.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        ListStores listStores = listStoresReading(id -> List.of());

        assertThatThrownBy(() -> listStores.forHousehold(MEMBER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }
}

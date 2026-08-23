package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ListMyHouseholds.HouseholdSummary;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the query side of
 * first-run routing (AC2): the CQRS composition of the ACL caller-lookup with the household-name
 * read model, and that it is side-effect free.
 */
class ListMyHouseholdsTest {

    private static final String RAW_KEYCLOAK_USER_ID = "anna-sub";

    @Test
    void forCaller_returnsEmptyForACallerWithZeroHouseholds() {
        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(new InMemoryMemberMappingRepository()), ids -> Map.of());

        assertThat(listMyHouseholds.forCaller(RAW_KEYCLOAK_USER_ID)).isEmpty();
    }

    @Test
    void forCaller_returnsTheCallersHouseholdsWithTheirNames() {
        KeycloakUserId keycloakUserId = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
        mappingRepository.seed(new MemberMapping(firstHousehold, MemberId.generate(), keycloakUserId));
        mappingRepository.seed(new MemberMapping(secondHousehold, MemberId.generate(), keycloakUserId));
        Map<HouseholdId, HouseholdName> names = new HashMap<>();
        names.put(firstHousehold, new HouseholdName("Familie Muster"));
        names.put(secondHousehold, new HouseholdName("WG Sonnenallee"));
        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(mappingRepository), ids -> names);

        List<HouseholdSummary> summaries = listMyHouseholds.forCaller(RAW_KEYCLOAK_USER_ID);

        assertThat(summaries)
                .containsExactlyInAnyOrder(
                        new HouseholdSummary(firstHousehold, "Familie Muster"),
                        new HouseholdSummary(secondHousehold, "WG Sonnenallee"));
    }

    @Test
    void forCaller_stillReturnsAHouseholdWhoseNameHasNotYetCaughtUpSoRoutingIsNotUnderCounted() {
        // Membership is authoritative (the ACL mapping); a lagging name projection must not shrink
        // the caller's household count and misroute them as having fewer households than they do.
        KeycloakUserId keycloakUserId = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);
        HouseholdId notYetProjected = HouseholdId.generate();
        InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
        mappingRepository.seed(new MemberMapping(notYetProjected, MemberId.generate(), keycloakUserId));
        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(mappingRepository), ids -> Map.of());

        assertThat(listMyHouseholds.forCaller(RAW_KEYCLOAK_USER_ID))
                .containsExactly(new HouseholdSummary(notYetProjected, ""));
    }
}

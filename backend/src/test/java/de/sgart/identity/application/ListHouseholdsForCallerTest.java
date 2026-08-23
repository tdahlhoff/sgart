package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test of the Identity ACL's caller-lookup port — pure, no framework or persistence
 * (CLAUDE.md §6). Proves AC2's routing input: zero, one, and many households for a caller.
 */
class ListHouseholdsForCallerTest {

    private static final String RAW_KEYCLOAK_USER_ID = "anna-sub";

    @Test
    void forCaller_returnsEmptyForAPersonWithNoHouseholds() {
        ListHouseholdsForCaller listHouseholdsForCaller =
                new ListHouseholdsForCaller(new InMemoryMemberMappingRepository());

        assertThat(listHouseholdsForCaller.forCaller(RAW_KEYCLOAK_USER_ID)).isEmpty();
    }

    @Test
    void forCaller_returnsEveryHouseholdThePersonBelongsTo() {
        KeycloakUserId keycloakUserId = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        repository.seed(new MemberMapping(firstHousehold, MemberId.generate(), keycloakUserId));
        repository.seed(new MemberMapping(secondHousehold, MemberId.generate(), keycloakUserId));
        ListHouseholdsForCaller listHouseholdsForCaller = new ListHouseholdsForCaller(repository);

        assertThat(listHouseholdsForCaller.forCaller(RAW_KEYCLOAK_USER_ID))
                .containsExactlyInAnyOrder(firstHousehold, secondHousehold);
    }
}

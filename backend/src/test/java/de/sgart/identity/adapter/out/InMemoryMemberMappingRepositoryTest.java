package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for the governance de-link methods (Story 4.3, T17) — the in-memory double
 * handler tests rely on. Mirrors {@code JdbcMemberMappingRepositoryTest}'s isolation/idempotency
 * cases.
 */
class InMemoryMemberMappingRepositoryTest {

    private final InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();

    @Test
    void deleteMappingByMember_removesOnlyThatMembersRowInThatHousehold() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId annaId = MemberId.generate();
        MemberId bobId = MemberId.generate();
        repository.save(new MemberMapping(householdId, annaId, new KeycloakUserId("anna-sub")));
        repository.save(new MemberMapping(householdId, bobId, new KeycloakUserId("bob-sub")));

        repository.deleteMappingByMember(householdId, annaId);

        assertThat(repository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
        assertThat(repository.findMemberId(new KeycloakUserId("bob-sub"), householdId)).contains(bobId);
    }

    @Test
    void deleteMappingByMember_isIdempotent() {
        HouseholdId householdId = HouseholdId.generate();

        repository.deleteMappingByMember(householdId, MemberId.generate());

        assertThat(repository.householdIdsFor(new KeycloakUserId("anna-sub"))).isEmpty();
    }

    @Test
    void deleteAllMappings_removesEveryRowForTheHouseholdAndNoneOfAnothers() {
        HouseholdId householdToDelete = HouseholdId.generate();
        HouseholdId otherHousehold = HouseholdId.generate();
        repository.save(new MemberMapping(householdToDelete, MemberId.generate(), new KeycloakUserId("anna-sub")));
        repository.save(new MemberMapping(householdToDelete, MemberId.generate(), new KeycloakUserId("bob-sub")));
        MemberId otherHouseholdMemberId = MemberId.generate();
        repository.save(new MemberMapping(otherHousehold, otherHouseholdMemberId, new KeycloakUserId("anna-sub")));

        repository.deleteAllMappings(householdToDelete);

        assertThat(repository.findMemberId(new KeycloakUserId("anna-sub"), householdToDelete)).isEmpty();
        assertThat(repository.findMemberId(new KeycloakUserId("bob-sub"), householdToDelete)).isEmpty();
        assertThat(repository.findMemberId(new KeycloakUserId("anna-sub"), otherHousehold))
                .contains(otherHouseholdMemberId);
    }

    @Test
    void deleteAllMappings_isIdempotent() {
        repository.deleteAllMappings(HouseholdId.generate());

        assertThat(repository.householdIdsFor(new KeycloakUserId("anna-sub"))).isEmpty();
    }
}

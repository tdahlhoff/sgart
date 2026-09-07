package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves {@link RetractMembership} delegates to the repository's governance
 * de-link methods exactly (Story 4.3, T18): {@link RetractMembership#retractMember} to {@code
 * deleteMappingByMember}, {@link RetractMembership#retractHousehold} to {@code deleteAllMappings}.
 */
class RetractMembershipTest {

    private final InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
    private final RetractMembership retractMembership = new RetractMembership(repository);

    @Test
    void retractMember_delegatesToDeleteMappingByMember() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId memberId = MemberId.generate();
        repository.save(new MemberMapping(householdId, memberId, new KeycloakUserId("anna-sub")));

        retractMembership.retractMember(householdId, memberId);

        assertThat(repository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
    }

    @Test
    void retractHousehold_delegatesToDeleteAllMappings() {
        HouseholdId householdId = HouseholdId.generate();
        repository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("anna-sub")));
        repository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("bob-sub")));

        retractMembership.retractHousehold(householdId);

        assertThat(repository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
        assertThat(repository.findMemberId(new KeycloakUserId("bob-sub"), householdId)).isEmpty();
    }
}

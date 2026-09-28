package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves {@link RetractMembership} delegates to the repository's governance
 * de-link methods exactly (Story 4.3, T18): {@link RetractMembership#retractMember} to {@code
 * deleteMappingByMember}, {@link RetractMembership#retractHousehold} to {@code deleteAllMappings};
 * and (Story 8.3) that the de-linked membership's nickname row is dropped alongside it.
 */
class RetractMembershipTest {

    private final InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
    private final InMemoryMembershipNicknameRepository nicknameRepository =
            new InMemoryMembershipNicknameRepository(repository);
    private final RetractMembership retractMembership = new RetractMembership(repository, nicknameRepository);

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

    @Test
    void retractMember_alsoDropsThatMembersNickname() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId memberId = MemberId.generate();
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        repository.save(new MemberMapping(householdId, memberId, keycloakUserId));
        nicknameRepository.save(new MembershipNickname(keycloakUserId, householdId, "Anna"));

        retractMembership.retractMember(householdId, memberId);

        assertThat(nicknameRepository.find(keycloakUserId, householdId)).isEmpty();
    }

    @Test
    void retractHousehold_alsoDropsEveryMembersNickname() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId annaId = new KeycloakUserId("anna-sub");
        KeycloakUserId bobId = new KeycloakUserId("bob-sub");
        repository.save(new MemberMapping(householdId, MemberId.generate(), annaId));
        repository.save(new MemberMapping(householdId, MemberId.generate(), bobId));
        nicknameRepository.save(new MembershipNickname(annaId, householdId, "Anna"));
        nicknameRepository.save(new MembershipNickname(bobId, householdId, "Bob"));

        retractMembership.retractHousehold(householdId);

        assertThat(nicknameRepository.find(annaId, householdId)).isEmpty();
        assertThat(nicknameRepository.find(bobId, householdId)).isEmpty();
    }
}

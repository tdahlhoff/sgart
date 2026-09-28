package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory Identity ACL, no framework or persistence (CLAUDE.md §6). Proves
 * {@link SetMembershipNickname} (Story 8.3): upserts for an existing member, rejects a non-member,
 * rejects invalid input.
 */
class SetMembershipNicknameTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final InMemoryMembershipNicknameRepository nicknameRepository =
            new InMemoryMembershipNicknameRepository(mappingRepository);
    private final SetMembershipNickname setMembershipNickname =
            new SetMembershipNickname(nicknameRepository, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void set_upsertsTheCallersNicknameForAHouseholdTheyBelongTo() {
        seedMembership();

        setMembershipNickname.set(MEMBER_SUB, householdId, "Papa");

        assertThat(nicknameRepository.find(new KeycloakUserId(MEMBER_SUB), householdId)).contains("Papa");
    }

    @Test
    void set_overwritesAPreviouslySetNickname() {
        seedMembership();
        setMembershipNickname.set(MEMBER_SUB, householdId, "Papa");

        setMembershipNickname.set(MEMBER_SUB, householdId, "Timo");

        assertThat(nicknameRepository.find(new KeycloakUserId(MEMBER_SUB), householdId)).contains("Timo");
    }

    @Test
    void set_rejectsACallerWhoIsNotAMemberOfTheHousehold() {
        assertThatThrownBy(() -> setMembershipNickname.set("stranger-sub", householdId, "Papa"))
                .isInstanceOf(NotAMemberException.class);
        assertThat(nicknameRepository.find(new KeycloakUserId("stranger-sub"), householdId)).isEmpty();
    }

    @Test
    void set_rejectsABlankNickname() {
        seedMembership();

        assertThatThrownBy(() -> setMembershipNickname.set(MEMBER_SUB, householdId, "   "))
                .isInstanceOf(InvalidMembershipNicknameException.class)
                .satisfies(exception -> assertThat(((InvalidMembershipNicknameException) exception)
                                .errorDescriptor()
                                .code())
                        .isEqualTo("nickname.required"));
    }

    @Test
    void set_rejectsAnOverLengthNickname() {
        seedMembership();
        String tooLong = "a".repeat(200);

        assertThatThrownBy(() -> setMembershipNickname.set(MEMBER_SUB, householdId, tooLong))
                .isInstanceOf(InvalidMembershipNicknameException.class)
                .satisfies(exception -> assertThat(((InvalidMembershipNicknameException) exception)
                                .errorDescriptor()
                                .code())
                        .isEqualTo("nickname.tooLong"));
    }

    /** I/O matrix, "Same nickname as another member" — no uniqueness enforcement (locked decision). */
    @Test
    void set_allowsTwoDifferentMembersOfTheSameHouseholdToShareTheIdenticalNickname() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("bob-sub")));

        setMembershipNickname.set(MEMBER_SUB, householdId, "Peter");
        setMembershipNickname.set("bob-sub", householdId, "Peter");

        assertThat(nicknameRepository.find(new KeycloakUserId(MEMBER_SUB), householdId)).contains("Peter");
        assertThat(nicknameRepository.find(new KeycloakUserId("bob-sub"), householdId)).contains("Peter");
    }

    /** I/O matrix, "Two households, different nicknames" — nicknames are per-membership, not per-account. */
    @Test
    void set_letsTheSamePersonHoldIndependentNicknamesInTwoDifferentHouseholds() {
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(firstHousehold, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        mappingRepository.save(new MemberMapping(secondHousehold, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        setMembershipNickname.set(MEMBER_SUB, firstHousehold, "Papa");
        setMembershipNickname.set(MEMBER_SUB, secondHousehold, "Timo");

        assertThat(nicknameRepository.find(new KeycloakUserId(MEMBER_SUB), firstHousehold)).contains("Papa");
        assertThat(nicknameRepository.find(new KeycloakUserId(MEMBER_SUB), secondHousehold)).contains("Timo");
    }
}

package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test of the Identity ACL's resolution port — pure, seeded with synthetic mappings, no
 * framework or persistence (CLAUDE.md §6). Proves AC2's resolution contract.
 */
class ResolveMemberIdentityTest {

    @Test
    void resolve_resolvesAKnownMappingToItsMemberId() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        MemberId expectedMemberId = MemberId.generate();
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        repository.seed(new MemberMapping(householdId, expectedMemberId, keycloakUserId));
        ResolveMemberIdentity resolveMemberIdentity = new ResolveMemberIdentity(repository);

        MemberId resolvedMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        assertThat(resolvedMemberId).isEqualTo(expectedMemberId);
    }

    @Test
    void resolve_rejectsAnUnknownKeycloakUserIdWithNotAMemberInsteadOfMintingANewId() {
        ResolveMemberIdentity resolveMemberIdentity =
                new ResolveMemberIdentity(new InMemoryMemberMappingRepository());

        assertThatThrownBy(() ->
                        resolveMemberIdentity.resolve(new KeycloakUserId("unknown-sub"), HouseholdId.generate()))
                .isInstanceOf(NotAMemberException.class)
                .satisfies(exception -> assertThat(((NotAMemberException) exception).errorDescriptor().code())
                        .isEqualTo("identity.notAMember"));
    }

    @Test
    void resolve_resolvesTheSamePersonToDifferentMemberIdsInDifferentHouseholds() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        MemberId memberIdInFirstHousehold = MemberId.generate();
        MemberId memberIdInSecondHousehold = MemberId.generate();
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        repository.seed(new MemberMapping(firstHousehold, memberIdInFirstHousehold, keycloakUserId));
        repository.seed(new MemberMapping(secondHousehold, memberIdInSecondHousehold, keycloakUserId));
        ResolveMemberIdentity resolveMemberIdentity = new ResolveMemberIdentity(repository);

        MemberId resolvedInFirstHousehold = resolveMemberIdentity.resolve(keycloakUserId, firstHousehold);
        MemberId resolvedInSecondHousehold = resolveMemberIdentity.resolve(keycloakUserId, secondHousehold);

        assertThat(resolvedInFirstHousehold).isEqualTo(memberIdInFirstHousehold);
        assertThat(resolvedInSecondHousehold).isEqualTo(memberIdInSecondHousehold);
        assertThat(resolvedInFirstHousehold).isNotEqualTo(resolvedInSecondHousehold);
    }
}

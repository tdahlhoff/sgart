package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory Identity ACL, no framework or persistence (CLAUDE.md §6). Proves
 * {@link ResolveMembershipNicknames} (Story 8.3): resolves a mix of set/unset members via the ACL
 * join, side-effect free.
 */
class ResolveMembershipNicknamesTest {

    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final InMemoryMembershipNicknameRepository nicknameRepository =
            new InMemoryMembershipNicknameRepository(mappingRepository);
    private final ResolveMembershipNicknames resolveMembershipNicknames =
            new ResolveMembershipNicknames(nicknameRepository);

    @Test
    void resolveFor_returnsTheSetNicknameForAMemberAndOmitsAnUnsetOne() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId annaMemberId = MemberId.generate();
        MemberId bobMemberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, annaMemberId, new KeycloakUserId("anna-sub")));
        mappingRepository.save(new MemberMapping(householdId, bobMemberId, new KeycloakUserId("bob-sub")));
        nicknameRepository.save(new MembershipNickname(new KeycloakUserId("anna-sub"), householdId, "Anna"));

        Map<MemberId, String> resolved =
                resolveMembershipNicknames.resolveFor(householdId, List.of(annaMemberId, bobMemberId));

        assertThat(resolved).containsExactly(Map.entry(annaMemberId, "Anna"));
    }

    @Test
    void resolveFor_isEmptyWhenNoMemberHasSetANickname() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId memberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, memberId, new KeycloakUserId("anna-sub")));

        assertThat(resolveMembershipNicknames.resolveFor(householdId, List.of(memberId))).isEmpty();
    }
}

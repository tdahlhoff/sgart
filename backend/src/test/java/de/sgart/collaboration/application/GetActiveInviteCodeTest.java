package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.query.GetActiveInviteCode;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — fake read models + in-memory Identity ACL, no framework or persistence
 * (CLAUDE.md §6). Proves the query path (Story 8.4): a member sees the household's active code with
 * {@code canReplace} derived from their roster role, a non-member is rejected, and the projection-lag
 * case (no row yet) surfaces as an empty {@code Optional} rather than a fabricated result.
 */
class GetActiveInviteCodeTest {

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final ResolveMemberIdentity resolveMemberIdentity = new ResolveMemberIdentity(mappingRepository);
    private final FakeInviteReadModel inviteReadModel = new FakeInviteReadModel();
    private final FakeHouseholdMemberReadModel householdMemberReadModel = new FakeHouseholdMemberReadModel();

    private final GetActiveInviteCode query =
            new GetActiveInviteCode(resolveMemberIdentity, inviteReadModel, householdMemberReadModel);

    private MemberId registerMember(String keycloakUserId, HouseholdRole role) {
        MemberId memberId = MemberId.generate();
        mappingRepository.seed(new MemberMapping(householdId, memberId, new KeycloakUserId(keycloakUserId)));
        householdMemberReadModel.members = concat(householdMemberReadModel.members, new MemberRoleView(memberId, role));
        return memberId;
    }

    private static List<MemberRoleView> concat(List<MemberRoleView> existing, MemberRoleView added) {
        return java.util.stream.Stream.concat(existing.stream(), java.util.stream.Stream.of(added)).toList();
    }

    @Test
    void forHousehold_anAdminSeesTheActiveCodeWithCanReplaceTrue() {
        registerMember("anna-sub", HouseholdRole.ADMIN);
        InviteId inviteId = InviteId.generate();
        inviteReadModel.activeInvite = Optional.of(new InviteView(inviteId));

        Optional<GetActiveInviteCode.ActiveInviteCode> result = query.forHousehold("anna-sub", householdId.toString());

        assertThat(result).isPresent();
        assertThat(result.get().inviteId()).isEqualTo(inviteId.toString());
        assertThat(result.get().canReplace()).isTrue();
    }

    @Test
    void forHousehold_aParticipantSeesTheActiveCodeWithCanReplaceFalse() {
        registerMember("bruno-sub", HouseholdRole.PARTICIPANT);
        InviteId inviteId = InviteId.generate();
        inviteReadModel.activeInvite = Optional.of(new InviteView(inviteId));

        Optional<GetActiveInviteCode.ActiveInviteCode> result = query.forHousehold("bruno-sub", householdId.toString());

        assertThat(result).isPresent();
        assertThat(result.get().canReplace()).isFalse();
    }

    @Test
    void forHousehold_rejectsANonMemberWithoutTouchingTheInviteReadModel() {
        assertThatThrownBy(() -> query.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_returnsEmptyWhileTheProjectionHasNotCaughtUpYet() {
        registerMember("anna-sub", HouseholdRole.ADMIN);
        // inviteReadModel.activeInvite stays at its default empty Optional — no row yet.

        Optional<GetActiveInviteCode.ActiveInviteCode> result = query.forHousehold("anna-sub", householdId.toString());

        assertThat(result).isEmpty();
    }

    private static final class FakeInviteReadModel implements InviteReadModel {
        private Optional<InviteView> activeInvite = Optional.empty();

        @Override
        public Optional<InviteView> activeInviteOf(HouseholdId householdId) {
            return activeInvite;
        }
    }

    private static final class FakeHouseholdMemberReadModel implements HouseholdMemberReadModel {
        private List<MemberRoleView> members = List.of();

        @Override
        public List<MemberRoleView> membersOf(HouseholdId householdId) {
            return members;
        }
    }
}

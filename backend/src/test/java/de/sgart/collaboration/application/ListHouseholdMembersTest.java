package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListHouseholdMembers;
import de.sgart.collaboration.application.query.ListHouseholdMembers.MemberSummary;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the member roster query
 * (AC8): each row carries only {@code memberId}/{@code role} (AD-6, decision 5) with the caller
 * flagged {@code isSelf}, rejects a non-member (403), and is side-effect free.
 */
class ListHouseholdMembersTest {

    private static final String CALLER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListHouseholdMembers listHouseholdMembersReading(HouseholdMemberReadModel readModel) {
        return new ListHouseholdMembers(new ResolveMemberIdentity(mappingRepository), readModel);
    }

    @Test
    void forHousehold_returnsEachMemberWithRoleAndFlagsTheCallerAsSelf() {
        MemberId callerMemberId = MemberId.generate();
        MemberId otherMemberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, callerMemberId, new KeycloakUserId(CALLER_SUB)));
        ListHouseholdMembers listHouseholdMembers = listHouseholdMembersReading(id -> List.of(
                new MemberRoleView(callerMemberId, HouseholdRole.ADMIN),
                new MemberRoleView(otherMemberId, HouseholdRole.PARTICIPANT)));

        List<MemberSummary> members = listHouseholdMembers.forHousehold(CALLER_SUB, householdId.toString());

        assertThat(members)
                .containsExactlyInAnyOrder(
                        new MemberSummary(callerMemberId.toString(), "ADMIN", true),
                        new MemberSummary(otherMemberId.toString(), "PARTICIPANT", false));
    }

    @Test
    void forHousehold_rejectsANonMemberWith403() {
        ListHouseholdMembers listHouseholdMembers = listHouseholdMembersReading(id -> List.of());

        assertThatThrownBy(() -> listHouseholdMembers.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        ListHouseholdMembers listHouseholdMembers = listHouseholdMembersReading(id -> List.of());

        assertThatThrownBy(() -> listHouseholdMembers.forHousehold(CALLER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }

    @Test
    void memberSummaryCarriesNoEmailOrDisplayNameComponent() {
        List<String> componentNames = java.util.Arrays.stream(MemberSummary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertThat(componentNames).noneMatch(name -> name.contains("email") || name.contains("displayname"));
    }
}

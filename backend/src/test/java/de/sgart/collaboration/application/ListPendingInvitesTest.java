package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListPendingInvites;
import de.sgart.collaboration.application.query.ListPendingInvites.PendingInvite;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the invite query
 * (AC6): it returns exactly what the pending-only read model yields (no email, AD-6), rejects a
 * non-member (403), is side-effect free, and isolates two households from each other.
 */
class ListPendingInvitesTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListPendingInvites listPendingInvitesReading(InviteReadModel inviteReadModel) {
        return new ListPendingInvites(new ResolveMemberIdentity(mappingRepository), inviteReadModel);
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void forHousehold_returnsThePendingInvitesTheReadModelYields() {
        seedMembership();
        InviteId inviteId = InviteId.generate();
        MemberId invitedBy = MemberId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        ListPendingInvites listPendingInvites = listPendingInvitesReading(
                id -> List.of(new InviteView(inviteId, invitedAt, invitedBy, "PENDING")));

        List<PendingInvite> invites = listPendingInvites.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(invites)
                .containsExactly(
                        new PendingInvite(inviteId.toString(), invitedAt.toString(), invitedBy.toString(), "PENDING"));
    }

    @Test
    void forHousehold_excludesExpiredOrAcceptedInvitesTheReadModelAlreadyFiltersOut() {
        seedMembership();
        ListPendingInvites listPendingInvites = listPendingInvitesReading(id -> List.of());

        assertThat(listPendingInvites.forHousehold(MEMBER_SUB, householdId.toString())).isEmpty();
    }

    @Test
    void forHousehold_rejectsANonMemberWith403() {
        ListPendingInvites listPendingInvites = listPendingInvitesReading(id -> List.of());

        assertThatThrownBy(() -> listPendingInvites.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        ListPendingInvites listPendingInvites = listPendingInvitesReading(id -> List.of());

        assertThatThrownBy(() -> listPendingInvites.forHousehold(MEMBER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }

    @Test
    void forHousehold_isolatesTwoHouseholdsPendingInvitesFromEachOther() {
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        InviteId inviteInFirst = InviteId.generate();
        InviteId inviteInSecond = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        Map<HouseholdId, List<InviteView>> invitesByHousehold = Map.of(
                householdId, List.of(new InviteView(inviteInFirst, invitedAt, MemberId.generate(), "PENDING")),
                otherHouseholdId, List.of(new InviteView(inviteInSecond, invitedAt, MemberId.generate(), "PENDING")));
        ListPendingInvites listPendingInvites =
                listPendingInvitesReading(id -> invitesByHousehold.getOrDefault(id, List.of()));

        assertThat(listPendingInvites.forHousehold(MEMBER_SUB, householdId.toString()))
                .extracting(PendingInvite::inviteId)
                .containsExactly(inviteInFirst.toString());
        assertThat(listPendingInvites.forHousehold(MEMBER_SUB, otherHouseholdId.toString()))
                .extracting(PendingInvite::inviteId)
                .containsExactly(inviteInSecond.toString());
    }
}

package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.DemoteMemberHandler;
import de.sgart.collaboration.application.command.LeaveHouseholdHandler;
import de.sgart.collaboration.application.command.PromoteMemberHandler;
import de.sgart.collaboration.application.command.RemoveMemberHandler;
import de.sgart.collaboration.application.query.ListHouseholdMembers;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member management (Story 4.3, AC2, AC3, AC4, AC8): members are nested under the household they
 * belong to (AD-10, mirrors {@code InviteController}). {@code GET} lists the member roster
 * (AC8, no PII); {@code DELETE /me} is self-leave (AC3, the client need not know its own {@code
 * MemberId}); {@code DELETE /{memberId}} is an Admin's removal of another member (AC4);
 * {@code POST .../promote}/{@code .../demote} are Admin-only role changes (AC4). Caller identity
 * comes only from the JWT {@code sub} via {@link AuthenticatedCaller} — never from the body/path
 * (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/members")
class MemberController {

    private final ListHouseholdMembers listHouseholdMembers;
    private final LeaveHouseholdHandler leaveHouseholdHandler;
    private final RemoveMemberHandler removeMemberHandler;
    private final PromoteMemberHandler promoteMemberHandler;
    private final DemoteMemberHandler demoteMemberHandler;

    MemberController(
            ListHouseholdMembers listHouseholdMembers,
            LeaveHouseholdHandler leaveHouseholdHandler,
            RemoveMemberHandler removeMemberHandler,
            PromoteMemberHandler promoteMemberHandler,
            DemoteMemberHandler demoteMemberHandler) {
        this.listHouseholdMembers = listHouseholdMembers;
        this.leaveHouseholdHandler = leaveHouseholdHandler;
        this.removeMemberHandler = removeMemberHandler;
        this.promoteMemberHandler = promoteMemberHandler;
        this.demoteMemberHandler = demoteMemberHandler;
    }

    @GetMapping
    List<MemberResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listHouseholdMembers.forHousehold(caller.keycloakUserId(), householdId).stream()
                .map(member -> new MemberResponse(member.memberId(), member.role(), member.isSelf()))
                .toList();
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody GovernanceCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        leaveHouseholdHandler.handle(caller.keycloakUserId(), householdId, request.commandId());
    }

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String memberId,
            @RequestBody GovernanceCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        removeMemberHandler.handle(caller.keycloakUserId(), householdId, memberId, request.commandId());
    }

    @PostMapping("/{memberId}/promote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void promote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String memberId,
            @RequestBody GovernanceCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        promoteMemberHandler.handle(caller.keycloakUserId(), householdId, memberId, request.commandId());
    }

    @PostMapping("/{memberId}/demote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void demote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String memberId,
            @RequestBody GovernanceCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        demoteMemberHandler.handle(caller.keycloakUserId(), householdId, memberId, request.commandId());
    }

    /** Transport DTO for every governance mutation here — the command envelope carries only the
     * client-generated {@code commandId} (AR10); no other body content. */
    record GovernanceCommandRequest(String commandId) {}

    /** No email/name in the response (AD-6, decision 5) — id + role + whether this row is the caller. */
    record MemberResponse(String memberId, String role, boolean isSelf) {}
}

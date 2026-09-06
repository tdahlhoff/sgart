package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.InvitePersonHandler;
import de.sgart.collaboration.application.query.ListPendingInvites;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invite management (Story 4.1, AC1/AC4/AC6/AC7): invites are nested under the household they
 * belong to — the aggregate that owns them (AD-10). {@code POST} sends an invite (the client minted
 * the {@code inviteId} and carries it, so the response needs no body — {@code 201});
 * {@code GET} lists the household's pending invites (AC6) — <strong>no email in the response</strong>
 * (AD-6). Caller identity comes only from the JWT {@code sub} via {@link AuthenticatedCaller} —
 * never from the body/path (AR10, AD-5). Mirrors {@code StoreController}.
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/invites")
class InviteController {

    private final InvitePersonHandler invitePersonHandler;
    private final ListPendingInvites listPendingInvites;

    InviteController(InvitePersonHandler invitePersonHandler, ListPendingInvites listPendingInvites) {
        this.invitePersonHandler = invitePersonHandler;
        this.listPendingInvites = listPendingInvites;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void invite(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody InviteRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler resolves the caller's MemberId (403 if not a member), enforces the
        // already-a-member seam (409, AC3) and the duplicate-pending/past-TTL invariants (409/AC2,
        // AC5), and validates the envelope + email (400).
        invitePersonHandler.handle(
                caller.keycloakUserId(), householdId, request.inviteId(), request.email(), request.commandId());
    }

    @GetMapping
    List<PendingInviteResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listPendingInvites.forHousehold(caller.keycloakUserId(), householdId).stream()
                .map(invite -> new PendingInviteResponse(
                        invite.inviteId(), invite.invitedAt(), invite.invitedBy(), invite.status()))
                .toList();
    }

    /** Transport DTO for {@code POST} — the invite command envelope (AR10). {@code inviteId} is the
     * client-minted id. */
    record InviteRequest(String inviteId, String email, String commandId) {}

    /** No email field — the invite read model carries none (AD-6, privacy-first, AC7). */
    record PendingInviteResponse(String inviteId, String invitedAt, String invitedBy, String status) {}
}

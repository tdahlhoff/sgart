package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.AcceptInviteHandler;
import de.sgart.collaboration.application.command.ReplaceInviteCodeHandler;
import de.sgart.collaboration.application.query.GetActiveInviteCode;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * The household's single, replaceable invite code (Story 8.4, F6/F7): every member may view and
 * share it ({@code GET .../invite-code}), any Admin may replace it ({@code POST
 * .../invite-code/replace}), and anyone holding it may redeem it ({@code POST
 * .../invites/{inviteId}/accept} — the accept path stays exactly where Story 4.2 put it, so
 * existing deep links and the web fallback page keep working). Caller identity comes only from the
 * JWT {@code sub} via {@link AuthenticatedCaller} — never from the body/path (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}")
class InviteController {

    private final GetActiveInviteCode getActiveInviteCode;
    private final ReplaceInviteCodeHandler replaceInviteCodeHandler;
    private final AcceptInviteHandler acceptInviteHandler;

    InviteController(
            GetActiveInviteCode getActiveInviteCode,
            ReplaceInviteCodeHandler replaceInviteCodeHandler,
            AcceptInviteHandler acceptInviteHandler) {
        this.getActiveInviteCode = getActiveInviteCode;
        this.replaceInviteCodeHandler = replaceInviteCodeHandler;
        this.acceptInviteHandler = acceptInviteHandler;
    }

    @GetMapping("/invite-code")
    ResponseEntity<ActiveInviteCodeResponse> activeInviteCode(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        Optional<GetActiveInviteCode.ActiveInviteCode> code =
                getActiveInviteCode.forHousehold(caller.keycloakUserId(), householdId);
        // Empty only while the projection has not caught up yet (eventual consistency, AR3/NFR9) —
        // every household has an active code from the moment it is created.
        return code.map(value -> ResponseEntity.ok(new ActiveInviteCodeResponse(value.inviteId(), value.canReplace())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/invite-code/replace")
    @ResponseStatus(HttpStatus.OK)
    void replace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody ReplaceInviteCodeRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler resolves the caller's MemberId (403 if not a member), enforces Admin-only
        // (403 governanceNotPermitted), and validates the envelope (400). No response body — the
        // client re-fetches the active code (read-your-writes via GET .../invite-code).
        replaceInviteCodeHandler.handle(
                caller.keycloakUserId(), householdId, request.newInviteId(), request.commandId());
    }

    @PostMapping("/invites/{inviteId}/accept")
    @ResponseStatus(HttpStatus.OK)
    void accept(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String inviteId,
            @RequestBody AcceptInviteRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler issues the joiner's MemberId (AD-5), checks it against the household's active
        // code (404 invite.notFound otherwise), and validates the envelope (400). No response body —
        // the client already holds householdId and re-bootstraps to route in.
        acceptInviteHandler.handle(caller.keycloakUserId(), householdId, inviteId, request.commandId());
    }

    /** Transport DTO for {@code GET .../invite-code} — no email, no list, no status (Story 8.4). */
    record ActiveInviteCodeResponse(String inviteId, boolean canReplace) {}

    /** Transport DTO for {@code POST .../invite-code/replace} — {@code newInviteId} is the
     * client-generated id for the replacement code. */
    record ReplaceInviteCodeRequest(String newInviteId, String commandId) {}

    /** Transport DTO for {@code POST .../invites/{inviteId}/accept} — no email/role (locked decision). */
    record AcceptInviteRequest(String commandId) {}
}

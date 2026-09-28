package de.sgart.identity.adapter.in;

import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import de.sgart.identity.application.SetMembershipNickname;
import de.sgart.shared.HouseholdId;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-household nickname capture (Story 8.3) — a separate controller from {@link
 * IdentityController} (SRP: that one is the live, persist-nothing {@code /me} identity read; this
 * one is a write to the new {@code membership_nickname} store, the documented AD-6 exception).
 * {@code keycloakUserId} comes only from the JWT {@code sub} via {@link AuthenticatedCaller} —
 * never the request path/body (AR10, AD-5): a person may set only their own nickname, for a
 * household they are already a member of.
 */
@RestController
@RequestMapping("/api/v1/identity/households/{householdId}/nickname")
class NicknameController {

    private final SetMembershipNickname setMembershipNickname;

    NicknameController(SetMembershipNickname setMembershipNickname) {
        this.setMembershipNickname = setMembershipNickname;
    }

    /** Required at onboarding (app-enforced), editable later from Profile. {@code 204} — no domain data returned. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void set(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId, @RequestBody NicknameRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        setMembershipNickname.set(caller.keycloakUserId(), HouseholdId.fromString(householdId), request.nickname());
    }

    /** Transport DTO — the self-chosen nickname to set for this household. */
    record NicknameRequest(String nickname) {}
}

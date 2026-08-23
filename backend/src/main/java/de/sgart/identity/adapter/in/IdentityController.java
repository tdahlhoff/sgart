package de.sgart.identity.adapter.in;

import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The app's canonical live-identity source (reused by the Profil screen 1.11 and the app header).
 * Deliberately household-less — it resolves no {@code MemberId} and touches no domain, since no
 * household exists yet at this point in Epic 1. It is also the end-to-end proof that JWT
 * validation and the {@code sub}-only caller seam work (AC1, AC2).
 *
 * <p>Persists nothing: display name and email are read live from the token and returned, never
 * written to any store (AD-6).
 */
@RestController
@RequestMapping("/api/v1/identity")
class IdentityController {

    @GetMapping("/me")
    IdentityResponse me(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        return new IdentityResponse(caller.keycloakUserId(), caller.displayName(), caller.email());
    }

    /** Transport DTO — deliberately the exact shape the client's {@code /me} call expects. */
    record IdentityResponse(String keycloakUserId, String displayName, String email) {}
}

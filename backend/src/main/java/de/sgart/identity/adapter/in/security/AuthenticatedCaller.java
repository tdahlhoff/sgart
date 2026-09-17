package de.sgart.identity.adapter.in.security;

import java.util.Objects;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller's identity, extracted only from a validated JWT — never from a request body, path,
 * or query parameter (AR10, "keycloakUserId never in request body/path — taken from the JWT
 * {@code sub}").
 *
 * <p>{@code displayName} and {@code email} are read live from token claims for display only; this
 * type is never persisted (AD-6). This is the single seam every {@code adapter.in} component uses
 * to learn who the caller is — no other component parses a {@link Jwt} directly.
 */
public record AuthenticatedCaller(String keycloakUserId, String displayName, String email, boolean emailVerified) {

    public AuthenticatedCaller {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(email, "email must not be null");
    }

    /**
     * Resolves the caller from the token's {@code sub} claim (opaque Keycloak user id) and the
     * live {@code name}/{@code preferred_username}, {@code email}, and {@code email_verified}
     * claims. {@code email_verified} (Story 7.3, review finding) reflects Keycloak's own
     * verification flag live, the same "read live, never persisted" discipline as the other
     * claims (AD-6) — it drives the Profil screen's not-attached / pending-confirmation / confirmed
     * distinction instead of an unreliable client-side seed.
     */
    public static AuthenticatedCaller fromJwt(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        String displayName = jwt.getClaimAsString("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = jwt.getClaimAsString("preferred_username");
        }
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        return new AuthenticatedCaller(
                keycloakUserId,
                displayName == null ? "" : displayName,
                email == null ? "" : email,
                emailVerified != null && emailVerified);
    }
}

package de.sgart.keycloak.authenticator;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.directgrant.AbstractDirectGrantAuthenticator;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * The Story 7.0/7.1 custom Direct-Grant authenticator: verifies an Ed25519-signed device challenge
 * instead of a password (AC2). Bound into the {@code sgart-device-direct-grant} flow
 * (keycloak/realm-sgart.json) after the built-in "Username Validation" execution, which resolves
 * {@link AuthenticationFlowContext#getUser()} from the {@code username} form parameter — this
 * authenticator never looks up the user itself.
 *
 * <p>One class serves as both {@link org.keycloak.authentication.Authenticator} and {@link
 * org.keycloak.authentication.AuthenticatorFactory} — {@link AbstractDirectGrantAuthenticator}'s
 * {@code create(KeycloakSession)} returns {@code this}, the same pattern every built-in Direct
 * Grant authenticator (e.g. {@code ValidatePassword}) follows. Registered via {@code
 * META-INF/services/org.keycloak.authentication.AuthenticatorFactory}.
 *
 * <p>Reads three form parameters carried in the single {@code grant_type=password} token POST
 * (D-B, "not a separate round-trip"): {@code timestamp} (epoch seconds), {@code nonce}, and {@code
 * signed_challenge} (the base64url Ed25519 signature over {@code username|timestamp|nonce}). The
 * public key it verifies against is read from the user's {@code publicKey} attribute (D-D).
 */
public final class DeviceSignedChallengeAuthenticator extends AbstractDirectGrantAuthenticator {

    public static final String PROVIDER_ID = "sgart-device-signed-challenge";

    static final String USERNAME_PARAM = "username";
    static final String TIMESTAMP_PARAM = "timestamp";
    static final String NONCE_PARAM = "nonce";
    static final String SIGNED_CHALLENGE_PARAM = "signed_challenge";
    static final String PUBLIC_KEY_ATTRIBUTE = "publicKey";

    /** D-B: "a tight time window (~60s)". */
    private static final Duration CHALLENGE_WINDOW = Duration.ofSeconds(60);

    private final NonceSeenCache nonceSeenCache = new NonceSeenCache(CHALLENGE_WINDOW);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            // The preceding Username Validation execution could not resolve a user. Fails exactly
            // like a bad signature below — no user-enumeration difference (Story 7.1 task list).
            fail(context, "no user resolved by the preceding Username Validation execution");
            return;
        }

        MultivaluedMap<String, String> formParameters = context.getHttpRequest().getDecodedFormParameters();
        // The exact `username` form parameter the client sent and signed over — NOT
        // user.getUsername(): Keycloak persists usernames case-normalized (lower-cased) by
        // default, so the stored value can differ from the base64url string (mixed-case alphabet)
        // the device actually signed. Reconstructing the message from the raw request keeps
        // verification independent of that storage-layer normalization.
        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                formParameters.getFirst(USERNAME_PARAM),
                user.getFirstAttribute(PUBLIC_KEY_ATTRIBUTE),
                formParameters.getFirst(TIMESTAMP_PARAM),
                formParameters.getFirst(NONCE_PARAM),
                formParameters.getFirst(SIGNED_CHALLENGE_PARAM),
                Instant.now(),
                CHALLENGE_WINDOW,
                nonceSeenCache);

        if (!result.isValid()) {
            fail(context, result.reason());
            return;
        }
        context.success();
    }

    /**
     * @param reason server-side-only detail ({@link ChallengeVerificationResult}'s own Javadoc) —
     *     recorded on the Keycloak event for operator diagnostics, never in the client-facing
     *     {@code errorResponse} below, so it cannot regress the no-user-enumeration guarantee.
     */
    private void fail(AuthenticationFlowContext context, String reason) {
        context.getEvent().detail("sgart_reason", reason);
        context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
        Response challengeResponse =
                errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(), "invalid_grant", "Invalid device credential");
        context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, challengeResponse);
    }

    @Override
    public boolean requiresUser() {
        // The preceding Username Validation execution already resolved (or failed to resolve) the
        // user; this authenticator tolerates a null user itself (see authenticate) so a missing
        // user fails through the same no-enumeration path as a bad signature, rather than Keycloak
        // short-circuiting the flow before this executes.
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return user.getFirstAttribute(PUBLIC_KEY_ATTRIBUTE) != null;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No required actions — a device-signed challenge has no interactive setup step.
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getDisplayType() {
        return "SGART Device-Signed Challenge";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED};
    }

    @Override
    public String getHelpText() {
        return "Verifies an Ed25519-signed device challenge for Story 7.1 browserless sign-in "
                + "instead of a password.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}

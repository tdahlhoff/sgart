package de.sgart.identity.adapter.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.sgart.identity.application.AccountDetails;
import de.sgart.identity.application.CreateAccount;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.application.InvalidAccountProvisioningException;
import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The Story 7.1 real implementation of {@link CreateAccount} and {@link DeleteAccount}, extended
 * in Story 7.3 with the R1-rebind Admin capabilities ({@link RebindAccountCredential}, {@link
 * GetAccountDetails}): creates, deletes, and mutates a Keycloak account via the Admin REST API, client-credentials
 * authenticated — same token-fetch shape as every other Keycloak Admin adapter in this package;
 * all Keycloak/HTTP types are contained in this
 * adapter (AD-1/AD-2). One class implements every port because all of them are the same "manage a
 * Keycloak account" responsibility over the same client-credentials token fetch (DRY, design §10
 * "extend the existing Keycloak Admin adapter, it already holds the token fetch and
 * manage-users") — the story's own naming pins the class to {@code KeycloakAdminCreateAccount}.
 *
 * <p>Config-gated behind {@code sgart.identity.keycloak-admin.enabled} in {@code
 * IdentityBeansConfig}, sharing that flag and its base-url/realm/client-id/client-secret with the
 * Story 4.6 lookup adapter — both talk to the same {@code sgart-admin} confidential client, which
 * this story adds {@code manage-users} to (alongside the existing {@code view-users}).
 */
public final class KeycloakAdminCreateAccount
        implements CreateAccount, DeleteAccount, RebindAccountCredential, GetAccountDetails {

    /** D-D: the Ed25519 public key is stored as a plain Keycloak user attribute (simplest for the
     * Direct-Grant authenticator SPI to read). */
    static final String PUBLIC_KEY_ATTRIBUTE = "publicKey";

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakAdminCreateAccount(RestClient restClient, String realm, String clientId, String clientSecret) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret must not be null");
    }

    @Override
    public KeycloakUserId create(String username, String publicKey) {
        String accessToken = fetchAccessToken();

        ResponseEntity<Void> response = restClient
                .post()
                .uri("/admin/realms/{realm}/users", realm)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateUserRequest(username, true, Map.of(PUBLIC_KEY_ATTRIBUTE, List.of(publicKey))))
                .retrieve()
                // Keycloak answers 409 when the username already exists — the idempotent-retry case
                // (AC3), not a real failure. Suppressing only this status keeps every other 4xx/5xx
                // throwing normally.
                .onStatus(status -> status.value() == 409, (request, ignoredResponse) -> {})
                .toBodilessEntity();

        if (response.getStatusCode().value() == 409) {
            return findUserIdByUsername(username, accessToken);
        }
        return new KeycloakUserId(userIdFromLocation(response));
    }

    @Override
    public void delete(KeycloakUserId keycloakUserId) {
        String accessToken = fetchAccessToken();
        restClient
                .delete()
                .uri("/admin/realms/{realm}/users/{id}", realm, keycloakUserId.value())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                // Already gone (a re-run sweep, or a manual admin deletion) is a no-op, not an error.
                .onStatus(status -> status.value() == 404, (request, ignoredResponse) -> {})
                .toBodilessEntity();
    }

    @Override
    public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
        updateUser(keycloakUserId, new RebindRequest(username, Map.of(PUBLIC_KEY_ATTRIBUTE, List.of(publicKey))));
    }

    @Override
    public Optional<AccountDetails> findById(KeycloakUserId keycloakUserId) {
        String accessToken = fetchAccessToken();
        ResponseEntity<UserDetailResponse> response = restClient
                .get()
                .uri("/admin/realms/{realm}/users/{id}", realm, keycloakUserId.value())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                // Already gone (a re-run sweep racing a deletion) is "not found", not an error.
                .onStatus(status -> status.value() == 404, (request, ignoredResponse) -> {})
                .toEntity(UserDetailResponse.class);
        UserDetailResponse user = response.getBody();
        if (response.getStatusCode().value() == 404 || user == null) {
            return Optional.empty();
        }
        String publicKey = user.attributes() == null ? null : firstOrNull(user.attributes().get(PUBLIC_KEY_ATTRIBUTE));
        return Optional.of(new AccountDetails(user.username(), publicKey));
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private void updateUser(KeycloakUserId keycloakUserId, Object requestBody) {
        String accessToken = fetchAccessToken();
        restClient
                .put()
                .uri("/admin/realms/{realm}/users/{id}", realm, keycloakUserId.value())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toBodilessEntity();
    }

    private KeycloakUserId findUserIdByUsername(String username, String accessToken) {
        KeycloakUserResponse[] users = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/realms/{realm}/users")
                        .queryParam("username", "{username}")
                        .queryParam("exact", true)
                        .build(realm, username))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(KeycloakUserResponse[].class);
        if (users == null || users.length != 1) {
            // Never a raw/unmapped 500 (endpoint intent, AC3): AccountErrorAdvice maps this
            // application exception the same way it maps every other provisioning failure.
            throw new InvalidAccountProvisioningException(
                    "account.provisioningConflict",
                    "Keycloak reported user '" + username + "' already exists, but the exact lookup found "
                            + (users == null ? 0 : users.length));
        }
        return new KeycloakUserId(users[0].id());
    }

    private static String userIdFromLocation(ResponseEntity<Void> response) {
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        Objects.requireNonNull(location, "Keycloak create-user response carried no Location header");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private String fetchAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        TokenResponse token = restClient
                .post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return Objects.requireNonNull(token, "token response must not be null").accessToken();
    }

    /** {@code enabled: true, no email/name} (D-A/AD-6) — nothing but the username and public key. */
    private record CreateUserRequest(String username, boolean enabled, Map<String, List<String>> attributes) {}

    /** Story 7.3 R1 rebind (design §1.1): {@code username} and {@code publicKey} together — never just the key. */
    private record RebindRequest(String username, Map<String, List<String>> attributes) {}

    private record KeycloakUserResponse(String id) {}

    private record UserDetailResponse(String username, Map<String, List<String>> attributes) {}

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}

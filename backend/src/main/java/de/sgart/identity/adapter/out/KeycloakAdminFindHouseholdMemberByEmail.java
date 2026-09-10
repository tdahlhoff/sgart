package de.sgart.identity.adapter.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The Story 4.6 (D4, AC4) real implementation of {@link FindHouseholdMemberByEmail}: resolves an
 * email to a Keycloak user via the Admin REST API, client-credentials authenticated, then maps
 * that user's id to a {@link MemberId} via {@link MemberMappingRepository} (AD-2 — the Identity
 * ACL is the sole holder of both the Keycloak user id and the mapping). Config-gated behind
 * {@code sgart.identity.keycloak-admin.enabled} in {@code IdentityBeansConfig}; {@link
 * DeferredFindHouseholdMemberByEmail} stays wired when disabled (the default), so no admin
 * credentials are needed to build or test.
 *
 * <p>All Keycloak/HTTP types stay inside this adapter (AD-1/AD-2) — the port itself is the plain
 * {@code (String, HouseholdId) -> Optional<MemberId>} {@link FindHouseholdMemberByEmail} contract.
 */
public final class KeycloakAdminFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {

    private final RestClient restClient;
    private final MemberMappingRepository memberMappingRepository;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakAdminFindHouseholdMemberByEmail(
            RestClient restClient,
            MemberMappingRepository memberMappingRepository,
            String realm,
            String clientId,
            String clientSecret) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret must not be null");
    }

    @Override
    public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
        String accessToken = fetchAccessToken();
        KeycloakUserResponse[] users = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/admin/realms/{realm}/users")
                        // The email is a URI variable (not a literal query value) so it is percent-
                        // encoded — otherwise a legal '+' in the local part reaches Keycloak as a
                        // space, the exact lookup misses, and an existing member is silently
                        // re-invitable (Story 4.6 review, AC4).
                        .queryParam("email", "{email}")
                        .queryParam("exact", true)
                        .build(realm, email))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(KeycloakUserResponse[].class);

        if (users == null || users.length != 1) {
            return Optional.empty();
        }
        KeycloakUserId keycloakUserId = new KeycloakUserId(users[0].id());
        return memberMappingRepository.findMemberId(keycloakUserId, householdId);
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

    private record KeycloakUserResponse(String id) {}

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}

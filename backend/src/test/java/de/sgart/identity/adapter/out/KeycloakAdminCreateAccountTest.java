package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.sgart.identity.domain.KeycloakUserId;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Stubbed-HTTP unit test (no live Keycloak, CLAUDE.md §6), mirroring {@link
 * KeycloakAdminFindHouseholdMemberByEmailTest}. Proves the Story 7.1 {@link
 * KeycloakAdminCreateAccount} adapter's create-or-reuse idempotency (AC3) and its delete-is-a-no-op
 * behaviour for the retention sweep (AC5).
 */
class KeycloakAdminCreateAccountTest {

    private static final String BASE_URL = "http://keycloak.example";
    private static final String REALM = "sgart";
    private static final String USERNAME = "device-public-key-base64url";
    private static final String PUBLIC_KEY = "device-public-key-base64url";

    private final RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final KeycloakAdminCreateAccount adapter =
            new KeycloakAdminCreateAccount(restClientBuilder.build(), REALM, "sgart-admin", "admin-secret");

    @Test
    void create_newUsername_createsTheAccountAndReturnsItsKeycloakUserId() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer stub-admin-token"))
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.attributes.publicKey[0]").value(PUBLIC_KEY))
                .andRespond(withCreatedEntity(URI.create(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1")));

        KeycloakUserId keycloakUserId = adapter.create(USERNAME, PUBLIC_KEY);

        assertThat(keycloakUserId).isEqualTo(new KeycloakUserId("kc-user-1"));
        mockServer.verify();
    }

    @Test
    void create_usernameAlreadyExists_isIdempotentAndReturnsTheExistingKeycloakUserId() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?username=" + USERNAME + "&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"kc-user-existing\"}]", MediaType.APPLICATION_JSON));

        KeycloakUserId keycloakUserId = adapter.create(USERNAME, PUBLIC_KEY);

        assertThat(keycloakUserId).isEqualTo(new KeycloakUserId("kc-user-existing"));
        mockServer.verify();
    }

    @Test
    void delete_existingAccount_sendsTheAdminDeleteRequest() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer stub-admin-token"))
                .andRespond(withNoContent());

        adapter.delete(new KeycloakUserId("kc-user-1"));

        mockServer.verify();
    }

    @Test
    void delete_alreadyGoneAccount_isANoOp() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        adapter.delete(new KeycloakUserId("kc-user-1"));

        mockServer.verify();
    }

    private void expectTokenFetch() {
        mockServer
                .expect(requestTo(BASE_URL + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stub-admin-token\",\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
    }
}

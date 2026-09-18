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

import de.sgart.identity.application.AccountDetails;
import de.sgart.identity.domain.KeycloakUserId;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Stubbed-HTTP unit test (no live Keycloak, CLAUDE.md §6). Proves the Story 7.1 {@link
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

    @Test
    void rebind_issuesThePutWithUsernameAndPublicKeyAttribute() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-A1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer stub-admin-token"))
                .andExpect(jsonPath("$.username").value("U2"))
                .andExpect(jsonPath("$.attributes.publicKey[0]").value("K2"))
                .andRespond(withNoContent());

        adapter.rebind(new KeycloakUserId("kc-user-A1"), "U2", "K2");

        mockServer.verify();
    }

    @Test
    void setEmail_issuesThePutWithEmailAndVerifiedFlag() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.email").value("person@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andRespond(withNoContent());

        adapter.setEmail(new KeycloakUserId("kc-user-1"), "person@example.com", false);

        mockServer.verify();
    }

    @Test
    void markEmailVerified_issuesThePutWithOnlyTheVerifiedFlag() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andRespond(withNoContent());

        adapter.markEmailVerified(new KeycloakUserId("kc-user-1"));

        mockServer.verify();
    }

    @Test
    void clearEmail_issuesThePutWithANullEmail() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andRespond(withNoContent());

        adapter.clearEmail(new KeycloakUserId("kc-user-1"));

        mockServer.verify();
    }

    @Test
    void findByEmail_exactMatchWithVerifiedEmail_returnsTheKeycloakUserId() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?email=person%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"kc-user-A1\",\"emailVerified\":true}]", MediaType.APPLICATION_JSON));

        Optional<KeycloakUserId> found = adapter.findByEmail("person@example.com");

        assertThat(found).contains(new KeycloakUserId("kc-user-A1"));
        mockServer.verify();
    }

    @Test
    void findByEmail_noMatch_returnsEmpty() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?email=nobody%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(adapter.findByEmail("nobody@example.com")).isEmpty();
        mockServer.verify();
    }

    @Test
    void findByEmail_matchWithUnverifiedEmail_returnsEmpty() {
        // Story 7.3 review finding: an unconfirmed/unproven attached address must never be
        // recovery-eligible.
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?email=person%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"kc-user-A1\",\"emailVerified\":false}]", MediaType.APPLICATION_JSON));

        assertThat(adapter.findByEmail("person@example.com")).isEmpty();
        mockServer.verify();
    }

    @Test
    void findByEmail_ambiguousMatch_returnsEmpty() {
        // Story 7.3 review finding: a duplicate exact-match result is treated as "not found" (D-H)
        // rather than picking one arbitrarily; logged so it is diagnosable, not silently swallowed.
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?email=person%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":\"kc-user-A1\",\"emailVerified\":true},{\"id\":\"kc-user-A2\",\"emailVerified\":true}]",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.findByEmail("person@example.com")).isEmpty();
        mockServer.verify();
    }

    @Test
    void findById_returnsUsernamePublicKeyAndEmailStatus() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-A2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"username\":\"U2\",\"email\":\"person@example.com\",\"emailVerified\":true,"
                                + "\"attributes\":{\"publicKey\":[\"K2\"]}}",
                        MediaType.APPLICATION_JSON));

        Optional<AccountDetails> details = adapter.findById(new KeycloakUserId("kc-user-A2"));

        assertThat(details).contains(new AccountDetails("U2", "K2", "person@example.com", true));
        mockServer.verify();
    }

    @Test
    void findById_accountGone_returnsEmpty() {
        expectTokenFetch();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users/kc-user-gone"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.findById(new KeycloakUserId("kc-user-gone"))).isEmpty();
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

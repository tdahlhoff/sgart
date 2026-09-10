package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Stubbed-HTTP unit test (no live Keycloak, CLAUDE.md §6) for the real Story 4.6 {@code
 * FindHouseholdMemberByEmail} adapter — {@link KeycloakAdminFindHouseholdMemberByEmailTest}
 * asserts the client-credentials token fetch and the {@code ?email=&exact=true} admin lookup call
 * shapes, then the mapping resolution through {@link InMemoryMemberMappingRepository}.
 */
class KeycloakAdminFindHouseholdMemberByEmailTest {

    private static final String BASE_URL = "http://keycloak.example";
    private static final String REALM = "sgart";
    private static final HouseholdId HOUSEHOLD_ID = HouseholdId.generate();

    private final RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

    @Test
    void forHousehold_emailBelongsToACurrentMember_returnsTheirMemberId() {
        MemberId annaId = MemberId.generate();
        InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
        memberMappingRepository.save(new MemberMapping(HOUSEHOLD_ID, annaId, new KeycloakUserId("keycloak-anna")));
        KeycloakAdminFindHouseholdMemberByEmail adapter = new KeycloakAdminFindHouseholdMemberByEmail(
                restClientBuilder.build(), memberMappingRepository, REALM, "sgart-admin", "admin-secret");

        expectTokenFetch();
        expectUserLookup("anna@example.com", "[{\"id\":\"keycloak-anna\"}]");

        Optional<MemberId> result = adapter.forHousehold("anna@example.com", HOUSEHOLD_ID);

        assertThat(result).contains(annaId);
        mockServer.verify();
    }

    @Test
    void forHousehold_emailBelongsToAKeycloakUserWhoIsNotAMemberOfThisHousehold_returnsEmpty() {
        InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
        KeycloakAdminFindHouseholdMemberByEmail adapter = new KeycloakAdminFindHouseholdMemberByEmail(
                restClientBuilder.build(), memberMappingRepository, REALM, "sgart-admin", "admin-secret");

        expectTokenFetch();
        expectUserLookup("stranger@example.com", "[{\"id\":\"keycloak-stranger\"}]");

        Optional<MemberId> result = adapter.forHousehold("stranger@example.com", HOUSEHOLD_ID);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void forHousehold_noKeycloakUserForEmail_returnsEmpty() {
        InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
        KeycloakAdminFindHouseholdMemberByEmail adapter = new KeycloakAdminFindHouseholdMemberByEmail(
                restClientBuilder.build(), memberMappingRepository, REALM, "sgart-admin", "admin-secret");

        expectTokenFetch();
        expectUserLookup("nobody@example.com", "[]");

        Optional<MemberId> result = adapter.forHousehold("nobody@example.com", HOUSEHOLD_ID);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void forHousehold_emailContainsAPlusSign_encodesItSoTheExactLookupStillMatches() {
        MemberId annaId = MemberId.generate();
        InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
        memberMappingRepository.save(new MemberMapping(HOUSEHOLD_ID, annaId, new KeycloakUserId("keycloak-anna")));
        KeycloakAdminFindHouseholdMemberByEmail adapter = new KeycloakAdminFindHouseholdMemberByEmail(
                restClientBuilder.build(), memberMappingRepository, REALM, "sgart-admin", "admin-secret");

        expectTokenFetch();
        expectUserLookup("anna+tag@example.com", "[{\"id\":\"keycloak-anna\"}]");

        Optional<MemberId> result = adapter.forHousehold("anna+tag@example.com", HOUSEHOLD_ID);

        assertThat(result).contains(annaId);
        mockServer.verify();
    }

    private void expectTokenFetch() {
        mockServer
                .expect(requestTo(BASE_URL + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stub-admin-token\",\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
    }

    private void expectUserLookup(String email, String responseBody) {
        // The adapter percent-encodes the email as a URI variable, so the wire form is encoded
        // (e.g. '@' -> %40, '+' -> %2B) — Keycloak decodes it back for the exact match.
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users?email=" + encodedEmail + "&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer stub-admin-token"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }
}

package de.sgart.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The Story 7.0 acceptance test (AC2), landing here per the story: a real Keycloak {@code
 * 26.7.0} container with the {@code backend/keycloak-authenticator} provider JAR mounted and the
 * real {@code keycloak/realm-sgart.json} imported (the same file the dev stack and production both
 * use — no test-only copy to drift from it) proves the whole custom Direct-Grant flow end-to-end:
 * an Admin-API-created account with a registered public key signs in with a device-signed
 * challenge and receives a JWT that {@code adapter.in}'s own {@link SecurityConfig#jwtDecoder}
 * accepts (signature, issuer, audience) — no browser, no password. A wrong signature and a
 * replayed nonce are both rejected.
 *
 * <p>Owns its own container lifecycle (Story 1.4's Keycloak-Testcontainers precedent) — never the
 * dev compose Keycloak. The provider JAR is built by a Gradle task dependency on {@code
 * :keycloak-authenticator:jar} (see {@code build.gradle.kts}), never a compile dependency (F2).
 */
@Testcontainers
class DeviceSignedChallengeAcceptanceTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final String REALM = "sgart";
    private static final String ADMIN_USERNAME = "test-admin";
    private static final String ADMIN_PASSWORD = "test-admin-password";

    @Container
    static final GenericContainer<?> KEYCLOAK = buildKeycloakContainer();

    private static KeyPair testDeviceKeyPair;
    private static String testDeviceUsername;
    private static String createdKeycloakUserId;

    private static GenericContainer<?> buildKeycloakContainer() {
        File providerJar = theOnlyJarIn(System.getProperty("sgart.test.keycloakAuthenticatorJarDir"));
        return new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.7.0"))
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USERNAME)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(providerJar.toPath()),
                        "/opt/keycloak/providers/sgart-keycloak-authenticator.jar")
                // The real, shipped realm config (backend's test working directory is `backend/`) —
                // proves the actual authenticationFlows/directGrantFlow/client wiring this story
                // ships, not a test-only stand-in that could silently drift from it.
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("../keycloak/realm-sgart.json")),
                        "/opt/keycloak/data/import/realm-sgart.json")
                .withCommand("start-dev", "--import-realm")
                // start-dev auto-builds when it detects a new provider jar — the first boot is
                // slower than the dev-compose norm, hence the generous timeout.
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forPort(8080)
                        .withStartupTimeout(STARTUP_TIMEOUT))
                .withStartupTimeout(STARTUP_TIMEOUT);
    }

    private static File theOnlyJarIn(String directoryPath) {
        File directory = new File(directoryPath);
        File[] jars = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length != 1) {
            throw new IllegalStateException(
                    "Expected exactly one keycloak-authenticator JAR in " + directoryPath + ", found "
                            + (jars == null ? 0 : jars.length) + " — run :keycloak-authenticator:jar first");
        }
        return jars[0];
    }

    @BeforeAll
    static void provisionADeviceAccountViaTheAdminApi() throws Exception {
        testDeviceKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        testDeviceUsername = base64UrlPublicKey(testDeviceKeyPair);

        String adminAccessToken = fetchMasterRealmAdminToken();
        createdKeycloakUserId = createUser(adminAccessToken, testDeviceUsername);
    }

    @Test
    void directGrantWithDeviceSignedChallenge_issuesJwtAcceptedByResourceServer() {
        String accessToken = signInWithChallenge(
                testDeviceUsername, testDeviceKeyPair.getPrivate(), Instant.now(), UUID.randomUUID().toString());

        Jwt jwt = decoder().decode(accessToken);

        assertThat(jwt.getSubject()).isEqualTo(createdKeycloakUserId);
        assertThat(jwt.getIssuer().toString()).isEqualTo(issuer());
        assertThat(jwt.getAudience()).contains("sgart-backend");
    }

    @Test
    void directGrantWithWrongSignature_isRejected() throws Exception {
        KeyPair unrelatedKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String nonce = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        // Signed with a key that is NOT the one registered as this user's publicKey attribute.
        String wrongSignature = sign(unrelatedKeyPair.getPrivate(), testDeviceUsername, timestamp, nonce);

        assertThatThrownBy(() -> requestToken(testDeviceUsername, timestamp, nonce, wrongSignature))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(exception ->
                        assertThat(((HttpClientErrorException) exception).getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void directGrantWithReplayedNonce_isRejected() {
        String nonce = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();

        // First use succeeds…
        String accessToken =
                signInWithChallenge(testDeviceUsername, testDeviceKeyPair.getPrivate(), timestamp, nonce);
        assertThat(accessToken).isNotBlank();

        // …a byte-identical replay of the same signed challenge must not.
        String signature = sign(testDeviceKeyPair.getPrivate(), testDeviceUsername, timestamp, nonce);
        assertThatThrownBy(() -> requestToken(testDeviceUsername, timestamp, nonce, signature))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(exception ->
                        assertThat(((HttpClientErrorException) exception).getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private String signInWithChallenge(String username, PrivateKey privateKey, Instant timestamp, String nonce) {
        String signature = sign(privateKey, username, timestamp, nonce);
        return requestToken(username, timestamp, nonce, signature);
    }

    private String requestToken(String username, Instant timestamp, String nonce, String signature) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "sgart-app");
        form.add("username", username);
        form.add("timestamp", String.valueOf(timestamp.getEpochSecond()));
        form.add("nonce", nonce);
        form.add("signed_challenge", signature);

        TokenResponse response = RestClient.builder()
                .baseUrl(baseUrl())
                .build()
                .post()
                .uri("/realms/{realm}/protocol/openid-connect/token", REALM)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return response.accessToken();
    }

    private static String sign(PrivateKey privateKey, String username, Instant timestamp, String nonce) {
        try {
            String message = username + "|" + timestamp.getEpochSecond() + "|" + nonce;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String fetchMasterRealmAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", ADMIN_USERNAME);
        form.add("password", ADMIN_PASSWORD);

        TokenResponse response = adminRestClient()
                .post()
                .uri("/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return response.accessToken();
    }

    private static String createUser(String adminAccessToken, String username) {
        var response = adminRestClient()
                .post()
                .uri("/admin/realms/{realm}/users", REALM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateUserRequest(
                        username, true, java.util.Map.of("publicKey", java.util.List.of(username))))
                .retrieve()
                .toBodilessEntity();
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** The base64url encoding of the raw 32-byte Ed25519 public key — the same value used both as
     * the Keycloak {@code publicKey} user attribute and the derived username (D-E). */
    private static String base64UrlPublicKey(KeyPair keyPair) {
        byte[] x509Encoded = keyPair.getPublic().getEncoded();
        byte[] rawPublicKey = new byte[32];
        System.arraycopy(x509Encoded, x509Encoded.length - 32, rawPublicKey, 0, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawPublicKey);
    }

    private static RestClient adminRestClient() {
        return RestClient.builder().baseUrl(baseUrl()).build();
    }

    private static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static String issuer() {
        return baseUrl() + "/realms/" + REALM;
    }

    private static JwtDecoder decoder() {
        String jwkSetUri = issuer() + "/protocol/openid-connect/certs";
        // Reuses the exact production validation (signature/issuer/audience) rather than
        // re-implementing it here — the real proof this is "accepted by adapter.in" (AC2).
        return new SecurityConfig().jwtDecoder(jwkSetUri, issuer(), "sgart-backend");
    }

    private record CreateUserRequest(String username, boolean enabled, java.util.Map<String, java.util.List<String>> attributes) {}

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}

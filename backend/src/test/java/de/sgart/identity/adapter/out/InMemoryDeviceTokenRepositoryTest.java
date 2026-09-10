package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for the in-memory {@link DeviceTokenRepository} double (CLAUDE.md §6), mirroring
 * {@link InMemoryMemberMappingRepositoryTest}'s style. The durable adapter's identical contract is
 * proven separately by {@code JdbcDeviceTokenRepositoryTest}.
 */
class InMemoryDeviceTokenRepositoryTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-09-10T10:00:00Z");

    private final InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();

    @Test
    void upsert_writesARowThatFindByTokenReadsBack() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        DeviceToken deviceToken = new DeviceToken(keycloakUserId, "token-1", DevicePlatform.ANDROID, REGISTERED_AT);

        repository.upsert(deviceToken);

        assertThat(repository.findByToken("token-1")).contains(deviceToken);
    }

    @Test
    void upsert_reRegisteringTheSameTokenRefreshesTheRowInsteadOfDuplicatingIt() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        repository.upsert(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.ANDROID, REGISTERED_AT));
        Instant refreshedAt = REGISTERED_AT.plusSeconds(60);

        repository.upsert(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.IOS, refreshedAt));

        assertThat(repository.findByKeycloakUserId(keycloakUserId)).hasSize(1);
        assertThat(repository.findByToken("token-1"))
                .contains(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.IOS, refreshedAt));
    }

    @Test
    void findByToken_isEmptyForAnUnknownToken() {
        assertThat(repository.findByToken("unknown")).isEmpty();
    }

    @Test
    void findByKeycloakUserId_returnsEveryDeviceForThatPersonOnly() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        KeycloakUserId bob = new KeycloakUserId("bob-sub");
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, REGISTERED_AT));
        repository.upsert(new DeviceToken(bob, "bob-phone", DevicePlatform.ANDROID, REGISTERED_AT));

        assertThat(repository.findByKeycloakUserId(anna))
                .extracting(DeviceToken::token)
                .containsExactlyInAnyOrder("anna-phone", "anna-tablet");
    }

    @Test
    void deleteByToken_removesOnlyThatTokenAndIsIdempotent() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, REGISTERED_AT));

        repository.deleteByToken("anna-phone");
        repository.deleteByToken("anna-phone"); // idempotent

        assertThat(repository.findByToken("anna-phone")).isEmpty();
        assertThat(repository.findByToken("anna-tablet")).isPresent();
    }

    /** The Epic-6 erasure hook (AD-7) — removes every device for a person, none of another's. */
    @Test
    void deleteAllForUser_removesEveryDeviceForThatPersonAndNoneOfAnothers() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        KeycloakUserId bob = new KeycloakUserId("bob-sub");
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, REGISTERED_AT));
        repository.upsert(new DeviceToken(bob, "bob-phone", DevicePlatform.ANDROID, REGISTERED_AT));

        repository.deleteAllForUser(anna);

        assertThat(repository.findByKeycloakUserId(anna)).isEmpty();
        assertThat(repository.findByKeycloakUserId(bob)).hasSize(1);
    }

    @Test
    void deleteAllForUser_isIdempotent() {
        repository.deleteAllForUser(new KeycloakUserId("stranger-sub"));

        assertThat(repository.findByKeycloakUserId(new KeycloakUserId("stranger-sub"))).isEmpty();
    }
}

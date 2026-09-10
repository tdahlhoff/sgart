package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Fast unit test (CLAUDE.md §6) for the sign-out unregister path (Story 4.5, AC5). */
class UnregisterDeviceTokenTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-09-10T10:00:00Z");

    private final InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();
    private final UnregisterDeviceToken unregisterDeviceToken = new UnregisterDeviceToken(repository);

    @Test
    void unregister_removesTheCallersOwnToken() {
        repository.upsert(new DeviceToken(new KeycloakUserId("anna-sub"), "token-1", DevicePlatform.ANDROID, REGISTERED_AT));

        unregisterDeviceToken.unregister("anna-sub", "token-1");

        assertThat(repository.findByToken("token-1")).isEmpty();
    }

    @Test
    void unregister_isIdempotentForAnAlreadyUnregisteredToken() {
        unregisterDeviceToken.unregister("anna-sub", "never-registered");

        assertThat(repository.findByKeycloakUserId(new KeycloakUserId("anna-sub"))).isEmpty();
    }

    /** A token another person owns is silently ignored — no ownership leak, no delete power over it. */
    @Test
    void unregister_neverDeletesAnotherPersonsToken() {
        repository.upsert(new DeviceToken(new KeycloakUserId("bob-sub"), "bobs-token", DevicePlatform.IOS, REGISTERED_AT));

        unregisterDeviceToken.unregister("anna-sub", "bobs-token");

        assertThat(repository.findByToken("bobs-token")).isPresent();
    }

    @Test
    void unregister_rejectsABlankToken() {
        assertThatThrownBy(() -> unregisterDeviceToken.unregister("anna-sub", " "))
                .isInstanceOf(InvalidDeviceRegistrationException.class);
    }
}

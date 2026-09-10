package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, {@link InMemoryDeviceTokenRepository} double, no framework (CLAUDE.md
 * §6). Proves {@link RegisterDeviceToken}'s upsert-by-token behavior and fail-fast validation
 * (Story 4.5, AC5).
 */
class RegisterDeviceTokenTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

    private final InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();
    private final RegisterDeviceToken registerDeviceToken =
            new RegisterDeviceToken(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void register_upsertsADeviceTokenForTheCaller() {
        registerDeviceToken.register("anna-sub", "token-1", "ANDROID");

        assertThat(repository.findByKeycloakUserId(new KeycloakUserId("anna-sub")))
                .singleElement()
                .satisfies(deviceToken -> {
                    assertThat(deviceToken.token()).isEqualTo("token-1");
                    assertThat(deviceToken.platform()).isEqualTo(DevicePlatform.ANDROID);
                    assertThat(deviceToken.registeredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void register_reRegisteringTheSameTokenRefreshesItInsteadOfDuplicatingIt() {
        registerDeviceToken.register("anna-sub", "token-1", "ANDROID");

        registerDeviceToken.register("anna-sub", "token-1", "IOS");

        assertThat(repository.findByKeycloakUserId(new KeycloakUserId("anna-sub"))).hasSize(1);
        assertThat(repository.findByToken("token-1")).get().extracting(deviceToken -> deviceToken.platform())
                .isEqualTo(DevicePlatform.IOS);
    }

    @Test
    void register_rejectsABlankToken() {
        assertThatThrownBy(() -> registerDeviceToken.register("anna-sub", "  ", "ANDROID"))
                .isInstanceOf(InvalidDeviceRegistrationException.class)
                .extracting(exception -> ((InvalidDeviceRegistrationException) exception).errorDescriptor().code())
                .isEqualTo("device.tokenRequired");
    }

    @Test
    void register_rejectsAMissingPlatform() {
        assertThatThrownBy(() -> registerDeviceToken.register("anna-sub", "token-1", null))
                .isInstanceOf(InvalidDeviceRegistrationException.class)
                .extracting(exception -> ((InvalidDeviceRegistrationException) exception).errorDescriptor().code())
                .isEqualTo("device.platformRequired");
    }

    @Test
    void register_rejectsAnUnrecognizedPlatform() {
        assertThatThrownBy(() -> registerDeviceToken.register("anna-sub", "token-1", "WINDOWS_PHONE"))
                .isInstanceOf(InvalidDeviceRegistrationException.class)
                .extracting(exception -> ((InvalidDeviceRegistrationException) exception).errorDescriptor().code())
                .isEqualTo("device.platformInvalid");
    }
}

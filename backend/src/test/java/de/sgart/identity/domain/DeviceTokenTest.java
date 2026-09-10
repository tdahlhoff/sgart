package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework/infra (CLAUDE.md §6). Proves the {@link DeviceToken} value
 * object's fail-fast validation (Story 4.5, AC5) and its data-minimization guarantee (CLAUDE.md
 * §5): no household/member field, ever.
 */
class DeviceTokenTest {

    private static final KeycloakUserId KEYCLOAK_USER_ID = new KeycloakUserId("anna-sub");
    private static final Instant REGISTERED_AT = Instant.parse("2026-09-10T10:00:00Z");

    @Test
    void constructor_acceptsAValidDeviceToken() {
        DeviceToken deviceToken = new DeviceToken(KEYCLOAK_USER_ID, "fcm-token-123", DevicePlatform.ANDROID, REGISTERED_AT);

        assertThat(deviceToken.keycloakUserId()).isEqualTo(KEYCLOAK_USER_ID);
        assertThat(deviceToken.token()).isEqualTo("fcm-token-123");
        assertThat(deviceToken.platform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(deviceToken.registeredAt()).isEqualTo(REGISTERED_AT);
    }

    @Test
    void constructor_rejectsANullKeycloakUserId() {
        assertThatThrownBy(() -> new DeviceToken(null, "token", DevicePlatform.IOS, REGISTERED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsANullToken() {
        assertThatThrownBy(() -> new DeviceToken(KEYCLOAK_USER_ID, null, DevicePlatform.IOS, REGISTERED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsABlankToken() {
        assertThatThrownBy(() -> new DeviceToken(KEYCLOAK_USER_ID, "   ", DevicePlatform.IOS, REGISTERED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsANullPlatform() {
        assertThatThrownBy(() -> new DeviceToken(KEYCLOAK_USER_ID, "token", null, REGISTERED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsANullRegisteredAt() {
        assertThatThrownBy(() -> new DeviceToken(KEYCLOAK_USER_ID, "token", DevicePlatform.IOS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void devicePlatform_isExactlyAndroidAndIos() {
        assertThat(DevicePlatform.values()).containsExactlyInAnyOrder(DevicePlatform.ANDROID, DevicePlatform.IOS);
    }

    /**
     * GDPR data-minimization guard (CLAUDE.md §5): a device token carries no household/member
     * field — only the person key, the opaque token, the platform, and the registration timestamp
     * (Story 4.5, AC5, "no household id, no MemberId on the token").
     */
    @Test
    void deviceToken_carriesNoHouseholdOrMemberField() {
        List<String> componentNames = Arrays.stream(DeviceToken.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactlyInAnyOrder("keycloakUserId", "token", "platform", "registeredAt");
    }
}

package de.sgart.identity.application;

import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/**
 * Registers or refreshes a device's push token (Story 4.5, AC5) — command-style, returns nothing
 * beyond success (CQRS §4). Re-registering the same {@code token} is an idempotent upsert (a
 * refreshed FCM/APNs token client-side re-registers under a new token value; the old one is
 * pruned separately by the transport's stale-token signal, not by this command).
 */
public final class RegisterDeviceToken {

    private final DeviceTokenRepository deviceTokenRepository;
    private final Clock clock;

    public RegisterDeviceToken(DeviceTokenRepository deviceTokenRepository, Clock clock) {
        this.deviceTokenRepository = Objects.requireNonNull(deviceTokenRepository, "deviceTokenRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     * @throws InvalidDeviceRegistrationException if {@code rawToken} is blank (400) or {@code
     *     rawPlatform} is blank/unrecognized (400)
     */
    public void register(String keycloakUserId, String rawToken, String rawPlatform) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        String token = validatedToken(rawToken);
        DevicePlatform platform = validatedPlatform(rawPlatform);

        deviceTokenRepository.upsert(
                new DeviceToken(new KeycloakUserId(keycloakUserId), token, platform, clock.instant()));
    }

    private static String validatedToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidDeviceRegistrationException("device.tokenRequired", "token must be provided");
        }
        return rawToken;
    }

    private static DevicePlatform validatedPlatform(String rawPlatform) {
        if (rawPlatform == null || rawPlatform.isBlank()) {
            throw new InvalidDeviceRegistrationException("device.platformRequired", "platform must be provided");
        }
        try {
            return DevicePlatform.valueOf(rawPlatform);
        } catch (IllegalArgumentException notAPlatform) {
            throw new InvalidDeviceRegistrationException(
                    "device.platformInvalid", "platform must be one of " + Arrays.toString(DevicePlatform.values()));
        }
    }
}

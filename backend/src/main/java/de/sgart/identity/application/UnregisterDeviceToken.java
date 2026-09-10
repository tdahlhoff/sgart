package de.sgart.identity.application;

import de.sgart.identity.domain.DeviceTokenRepository;
import java.util.Objects;

/**
 * Unregisters a device's push token (Story 4.5, AC5) — the sign-out path. Scoped to the caller: a
 * token that exists but belongs to a different person is silently ignored (idempotent, no
 * ownership leak) rather than deleted, mirroring the "not found is a no-op" idempotency style used
 * throughout the Identity ACL (e.g. {@code MemberMappingRepository.deleteMapping}). System-driven
 * pruning of a stale/invalid token (the transport's rejection signal, AC5) is a distinct use case —
 * see {@link PruneDeviceToken}, which has no caller to scope against.
 */
public final class UnregisterDeviceToken {

    private final DeviceTokenRepository deviceTokenRepository;

    public UnregisterDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = Objects.requireNonNull(deviceTokenRepository, "deviceTokenRepository must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidDeviceRegistrationException if {@code rawToken} is blank (400)
     */
    public void unregister(String keycloakUserId, String rawToken) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidDeviceRegistrationException("device.tokenRequired", "token must be provided");
        }

        deviceTokenRepository
                .findByToken(rawToken)
                .filter(deviceToken -> deviceToken.keycloakUserId().value().equals(keycloakUserId))
                .ifPresent(deviceToken -> deviceTokenRepository.deleteByToken(rawToken));
    }
}

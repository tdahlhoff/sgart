package de.sgart.identity.application;

import de.sgart.identity.domain.DeviceTokenRepository;
import java.util.Objects;

/**
 * The system-driven counterpart to {@link UnregisterDeviceToken} (Story 4.5, AC5): removes a
 * device token the push transport itself reported invalid/unregistered (e.g. FCM's
 * {@code UNREGISTERED} error), not a user action. No caller/ownership to scope against — the
 * transport already proved the token is dead. The published port the notification fan-out
 * (Collaboration, {@code adapter.out}) calls after a {@code
 * ContentFreePushSender.send} returns {@code TOKEN_INVALID}, so the cross-context boundary stays
 * an application-layer port, never a direct reach into {@code identity.domain} (AD-2).
 */
public final class PruneDeviceToken {

    private final DeviceTokenRepository deviceTokenRepository;

    public PruneDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = Objects.requireNonNull(deviceTokenRepository, "deviceTokenRepository must not be null");
    }

    /** Idempotent — pruning an already-gone token is a safe no-op. */
    public void prune(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        deviceTokenRepository.deleteByToken(token);
    }
}

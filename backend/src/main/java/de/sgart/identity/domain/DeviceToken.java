package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A person's registered push-notification device (Story 4.5, AC5) — keyed by {@link
 * KeycloakUserId}, <strong>not</strong> {@link de.sgart.shared.MemberId}: a device belongs to a
 * person, not to any one household, and serves every household the person is a member of. Carries
 * the data-minimization set only: the opaque {@code token} (never logged, AD-6), the {@link
 * DevicePlatform}, and {@code registeredAt} (for retention/observability) — no household id, no
 * display name, no email.
 *
 * <p>Purpose (CLAUDE.md §5, data minimization/purpose limitation): deliver a content-free
 * wake-and-fetch push (AC1) when the person is not holding a live SSE connection. Retention: the
 * life of the registration — replaced on refresh (re-register with the same token upserts {@code
 * registeredAt}), pruned when the transport reports the token invalid/unregistered (AC5), and
 * removable in bulk for a {@link KeycloakUserId} on account erasure (Epic 6 hook, AD-7).
 */
public record DeviceToken(KeycloakUserId keycloakUserId, String token, DevicePlatform platform, Instant registeredAt) {

    public DeviceToken {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");
    }
}

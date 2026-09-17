package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored one-time code row (Story 7.3, design §4) — PII-free: keyed by the pseudonymous {@link
 * KeycloakUserId}, no email column. {@code codeHash} is {@code HMAC-SHA256(server secret, code)},
 * never the plaintext code. {@code attempts} counts wrong guesses; a row is rejected once {@code
 * attempts} reaches the application service's configured maximum or {@code expiresAt} has passed.
 */
public record EmailRecoveryCode(
        KeycloakUserId keycloakUserId,
        RecoveryCodePurpose purpose,
        String codeHash,
        Instant expiresAt,
        int attempts,
        Instant createdAt) {

    public EmailRecoveryCode {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(codeHash, "codeHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}

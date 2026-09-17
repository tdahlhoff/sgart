package de.sgart.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Generates and verifies 6-digit numeric one-time codes (Story 7.3, D-E / design §4: "6-digit
 * numeric · 15 min TTL · max 5 attempts · HMAC-SHA256 stored"). Shared by every issuing service
 * ({@link AttachRecoveryEmail}, {@link RequestEmailRecoveryCode}) and every verifying service
 * ({@link ConfirmRecoveryEmail}, {@link ConfirmEmailRecovery}) — DRY over the one security-
 * sensitive rule set, rather than four services each reimplementing it.
 */
final class RecoveryCode {

    static final Duration TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCode() {}

    /** @return a fresh 6-digit numeric code, e.g. {@code "042817"}. */
    static String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static Instant expiresAt(Clock clock) {
        return clock.instant().plus(TTL);
    }

    /** Whether {@code candidate}, hashed with {@code hasher}, matches an unexpired, non-exhausted stored row. */
    static boolean matches(
            de.sgart.identity.domain.EmailRecoveryCode stored, String candidate, RecoveryCodeHasher hasher, Clock clock) {
        Objects.requireNonNull(stored, "stored must not be null");
        if (stored.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        if (clock.instant().isAfter(stored.expiresAt())) {
            return false;
        }
        // Constant-time comparison (CLAUDE.md §5 security-by-design, Story 7.3 review finding): a
        // hash-length string comparison should never leak byte-position timing, even though the
        // hash itself already collapses the 6-digit code space.
        return MessageDigest.isEqual(
                hasher.hash(candidate).getBytes(StandardCharsets.UTF_8),
                stored.codeHash().getBytes(StandardCharsets.UTF_8));
    }
}

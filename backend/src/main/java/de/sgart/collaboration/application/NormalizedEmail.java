package de.sgart.collaboration.application;

import de.sgart.collaboration.application.exception.InvalidInviteEmailApplicationException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A trimmed, lowercased invite email (Story 4.1, AC1/AC3) — lives in {@code application}, never in
 * {@code collaboration.domain} (AD-6: the domain cannot see an email at all). Normalization matters
 * for both privacy (AD-6) and correctness: {@link de.sgart.collaboration.application.InviteEmailHasher}
 * hashes the normalized form, so "Anna@Example.com" and " anna@example.com " must hash identically
 * for the AC2 duplicate-pending check to work.
 *
 * <p>Fail-fast: a malformed address is rejected here, in the application layer, so it never reaches
 * the domain or the side-store. This is a shallow syntactic check (a real deliverability check is
 * out of scope) — {@link #EMAIL_PATTERN} rejects the obviously malformed without over-validating.
 */
public record NormalizedEmail(String value) {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Matches the side-store's {@code VARCHAR(320)} column — an over-long address must be rejected
     * here, before the event is appended, not left to fail on the side-store insert afterwards. */
    private static final int MAX_LENGTH = 320;

    public NormalizedEmail {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * @throws InvalidInviteEmailApplicationException if {@code rawEmail} is blank, too long, or
     *     malformed (400)
     */
    public static NormalizedEmail fromRaw(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new InvalidInviteEmailApplicationException(
                    "invite.emailRequired", "email must be provided");
        }
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_LENGTH) {
            throw new InvalidInviteEmailApplicationException(
                    "invite.emailInvalid", "email must be a valid address");
        }
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new InvalidInviteEmailApplicationException(
                    "invite.emailInvalid", "email must be a valid address");
        }
        return new NormalizedEmail(normalized);
    }
}

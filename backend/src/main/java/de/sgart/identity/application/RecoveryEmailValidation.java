package de.sgart.identity.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fail-fast email validation shared by {@link AttachRecoveryEmail} and {@link
 * RequestEmailRecoveryCode} (Story 7.3, AC1: "a malformed email is rejected fast (4xx), never
 * 500"). Deliberately permissive — this is a UX guard against typos, not the source of security
 * truth (ownership is proven by the one-time code, not by the shape of the address).
 */
final class RecoveryEmailValidation {

    /** The longest address RFC 5321 allows. */
    private static final int MAXIMUM_LENGTH = 254;

    private static final Pattern PLAUSIBLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private RecoveryEmailValidation() {}

    /**
     * @return the normalized address: trimmed and lowercased as a whole. There is deliberately no
     *     provider-specific folding (no dot or plus-tag stripping), which keeps it predictable.
     * @throws InvalidRecoveryEmailException if blank, too long, or not plausible.
     */
    static String validated(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new InvalidRecoveryEmailException("account.recoveryEmailRequired", "email must be provided");
        }
        String trimmed = rawEmail.trim();
        if (trimmed.length() > MAXIMUM_LENGTH || !PLAUSIBLE_EMAIL.matcher(trimmed).matches()) {
            throw new InvalidRecoveryEmailException("account.recoveryEmailInvalid", "email must be a plausible address");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}

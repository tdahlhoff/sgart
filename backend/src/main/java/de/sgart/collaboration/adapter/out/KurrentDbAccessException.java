package de.sgart.collaboration.adapter.out;

/**
 * Wraps an unexpected KurrentDB client/transport failure — not a business-rule outcome (unlike
 * {@link de.sgart.shared.ConcurrencyConflictException}), so it carries no {@code ErrorDescriptor};
 * an unhandled infrastructure fault surfaces as a generic error.
 */
final class KurrentDbAccessException extends RuntimeException {

    KurrentDbAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}

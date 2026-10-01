package de.sgart.identity.domain;

import java.util.Objects;

/**
 * A masked display form of a recovery address (for example {@code t***@e***.test}) so the
 * profile can show which address is attached without SGART storing the address itself. Computed
 * once when the address is typed; it reveals only the first character (code point) of the local part, the first
 * character of the domain name, and the top-level domain (the text after the last dot).
 */
public record RecoveryEmailHint(String value) {

    private static final String MASK = "***";

    public RecoveryEmailHint {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    /** @param address a plausible, already validated address ({@code local@domain}). */
    public static RecoveryEmailHint masking(String address) {
        Objects.requireNonNull(address, "address must not be null");
        int separatorIndex = address.lastIndexOf('@');
        if (separatorIndex < 1) {
            throw new IllegalArgumentException("address must contain a local part and a domain");
        }
        String localPart = address.substring(0, separatorIndex);
        String domain = address.substring(separatorIndex + 1);
        return new RecoveryEmailHint(maskedFirstCodePoint(localPart) + "@" + maskedDomain(domain));
    }

    private static String maskedDomain(String domain) {
        int lastDotIndex = domain.lastIndexOf('.');
        if (lastDotIndex < 1) {
            return maskedFirstCodePoint(domain);
        }
        return maskedFirstCodePoint(domain) + domain.substring(lastDotIndex);
    }

    private static String maskedFirstCodePoint(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("address must contain a local part and a domain");
        }
        return text.substring(0, text.offsetByCodePoints(0, 1)) + MASK;
    }

    /** Redacted: even a masked address stays out of logs and exception messages. */
    @Override
    public String toString() {
        return "RecoveryEmailHint[redacted]";
    }
}

package de.sgart.identity.domain;

import java.util.Objects;

/**
 * A masked display form of a recovery address (for example {@code t***@example.test}) so the
 * profile can show which address is attached without SGART storing the address itself. Computed
 * once when the address is typed; it reveals only the first character (code point) of the local part and the
 * domain.
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
        String firstCharacter = address.substring(0, address.offsetByCodePoints(0, 1));
        return new RecoveryEmailHint(firstCharacter + MASK + address.substring(separatorIndex));
    }

    /** Redacted: even a masked address stays out of logs and exception messages. */
    @Override
    public String toString() {
        return "RecoveryEmailHint[redacted]";
    }
}

package de.sgart.shared;

import java.util.Map;
import java.util.Objects;

/**
 * Canonical error shape {@code { code, message, details }} (Consistency Conventions).
 *
 * <p>{@code code} is a stable, client-facing machine key that the client maps to localized copy.
 * {@code message} is for logs and debugging only and is never shown to a user. {@code details}
 * carries optional structured context and must not contain personal data.
 */
public record ErrorDescriptor(String code, String message, Map<String, Object> details) {

    public ErrorDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ErrorDescriptor of(String code, String message) {
        return new ErrorDescriptor(code, message, Map.of());
    }
}

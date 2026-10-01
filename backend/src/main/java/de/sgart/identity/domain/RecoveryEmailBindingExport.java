package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The data-portability view of a binding: what a person may take with them. It deliberately
 * carries no digest, which is an internal lookup key and not something the person provided.
 * {@code confirmedAt} is {@code null} for a binding that was never confirmed.
 */
public record RecoveryEmailBindingExport(RecoveryEmailHint hint, Instant confirmedAt) {

    public RecoveryEmailBindingExport {
        Objects.requireNonNull(hint, "hint must not be null");
    }
}

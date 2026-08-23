package de.sgart.shared;

import java.util.Map;
import java.util.Objects;

/**
 * Raised by {@link EventStore#append} when a stream has advanced past the command's
 * {@code basedOnVersion} — a stale write. The write layer rejects it outright: never silently
 * applied, never last-writer-wins, never auto-merged (AD-8). The coarse keep/discard resolution UI
 * is Epic 5; here the write side only guarantees the rejection.
 *
 * <p>Carries the canonical {@link ErrorDescriptor} with the stable code {@code concurrency.staleVersion}
 * — the same pattern {@code NotAMemberException} used in Story 1.4 — so an Epic 5 conflict UI can
 * localize it with no backend change.
 */
public final class ConcurrencyConflictException extends RuntimeException {

    public static final String ERROR_CODE = "concurrency.staleVersion";

    private final ErrorDescriptor errorDescriptor;

    public ConcurrencyConflictException(AggregateVersion expectedVersion, AggregateVersion actualVersion) {
        super("Stale write on stream %s: expected version %d but stream is at %d".formatted(
                Objects.requireNonNull(expectedVersion, "expectedVersion must not be null")
                        .streamId()
                        .key(),
                expectedVersion.value(),
                Objects.requireNonNull(actualVersion, "actualVersion must not be null").value()));
        this.errorDescriptor = new ErrorDescriptor(
                ERROR_CODE,
                getMessage(),
                Map.of(
                        "expectedVersion", expectedVersion.value(),
                        "actualVersion", actualVersion.value()));
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}

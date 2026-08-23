package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque identity of a single domain event. UUID-backed and carrying no domain meaning.
 *
 * <p>Every emitted {@link DomainEvent} carries one. It is the input to
 * {@link CommandId#deterministicFrom(EventId)}: a process manager reacting to an event derives its
 * follow-up command's id from this value, so re-delivering the same event on subscription or replay
 * produces the same {@link CommandId} and the effect is applied exactly once (AD-10).
 */
public record EventId(UUID value) {

    public EventId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static EventId generate() {
        return new EventId(UUID.randomUUID());
    }

    public static EventId fromString(String value) {
        return new EventId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

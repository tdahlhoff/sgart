package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed key of an event-store stream. Encodes the spine convention {@code household-{id}} /
 * {@code list-{id}} / {@code trip-{id}} (AR10) as one unambiguous value so no handler hand-builds a
 * stream-key string.
 *
 * <p>Only {@link #forHousehold(HouseholdId)} exists today — households are the first aggregate
 * (Story 1.6). {@code forList}/{@code forTrip} factories arrive with their id types in Epics 2 and 3;
 * the {@link StreamType} prefixes are already fixed here because the convention is the whole point.
 */
public record StreamId(StreamType type, UUID id) {

    public StreamId {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    public static StreamId forHousehold(HouseholdId householdId) {
        Objects.requireNonNull(householdId, "householdId must not be null");
        return new StreamId(StreamType.HOUSEHOLD, householdId.value());
    }

    /** The wire/store key, e.g. {@code household-3f2504e0-4f89-41d3-9a0c-0305e82c3301}. */
    public String key() {
        return type.prefix() + "-" + id;
    }

    @Override
    public String toString() {
        return key();
    }

    /** The aggregate kinds that own event streams (AR10). */
    public enum StreamType {
        HOUSEHOLD("household"),
        LIST("list"),
        TRIP("trip");

        private final String prefix;

        StreamType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }
}

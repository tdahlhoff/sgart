package de.sgart.shared;

import java.util.Objects;

/**
 * Optimistic-concurrency token for one specific aggregate's event stream — the
 * {@code basedOnVersion} a command is built on and the expected version an append is checked
 * against (AD-8).
 *
 * <p>Carries the {@link StreamId} of the stream it belongs to alongside the numeric version, so a
 * version read from one aggregate cannot be silently substituted for another's: two versions with
 * the same {@code value} but different {@link #streamId()} are never equal, and
 * {@link EventStore#append} derives the stream it writes to directly from the version it is given
 * — there is no second, independently-suppliable stream id to disagree with it (AD-8: "never a
 * related aggregate's version").
 *
 * <p>Modelled as the count of events the stream has applied: {@link #initial(StreamId)} (zero) is
 * the "new stream" sentinel meaning <em>no events yet</em> — the version an append of a brand-new
 * aggregate expects — and {@link #next()} advances by one event during replay or append. The value
 * is never negative (fail fast).
 */
public record AggregateVersion(StreamId streamId, long value) {

    public AggregateVersion {
        Objects.requireNonNull(streamId, "streamId must not be null");
        if (value < 0) {
            throw new IllegalArgumentException("AggregateVersion must not be negative: " + value);
        }
    }

    /** The new-stream sentinel: no events yet. An append of a brand-new aggregate expects this. */
    public static AggregateVersion initial(StreamId streamId) {
        return new AggregateVersion(streamId, 0);
    }

    public static AggregateVersion of(StreamId streamId, long value) {
        return new AggregateVersion(streamId, value);
    }

    public boolean isInitial() {
        return value == 0;
    }

    /** The version after one more event is applied, for the same stream. */
    public AggregateVersion next() {
        return new AggregateVersion(streamId, value + 1);
    }
}

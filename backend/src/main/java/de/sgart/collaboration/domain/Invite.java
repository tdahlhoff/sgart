package de.sgart.collaboration.domain;

import java.time.Duration;

/**
 * Holds the invite time-to-live policy (Story 4.1, AC5, locked decision 4) — a domain constant so
 * expiry is computed deterministically by {@link Household#invitePerson}, never read from
 * configuration or wall-clock time inside the aggregate. The invite entity's folded state itself
 * lives inline in {@link Household} (its {@code InviteState}), mirroring {@code StoreState} — this
 * class exists only to give the TTL a name the aggregate reads.
 */
public final class Invite {

    /** An invite is pending for 7 days after it is sent (locked decision 4). */
    public static final Duration TIME_TO_LIVE = Duration.ofDays(7);

    private Invite() {}
}

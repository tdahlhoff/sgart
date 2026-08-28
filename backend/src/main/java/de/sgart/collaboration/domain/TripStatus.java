package de.sgart.collaboration.domain;

/**
 * A shopping trip's lifecycle state (glossary term, Story 3.1). Only {@code ACTIVE} is reachable
 * in 3.1 — {@code TripStarted} is the sole state-producing event, and it always folds to {@code
 * ACTIVE}. {@code DONE} (the trip completes) is Story 3.4's transition; the vocabulary exists from
 * the aggregate's birth, but no 3.1 command drives a trip out of {@code ACTIVE} (mirrors {@link
 * ListStatus}'s Story 2.1 restraint — do not fabricate a completion path to reach it early).
 */
public enum TripStatus {
    ACTIVE,
    DONE
}

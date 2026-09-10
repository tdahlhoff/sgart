package de.sgart.collaboration.application;

import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * The content-free wake-and-fetch signal a push carries (Story 4.5, AC1) — the background-push
 * twin of the live-sync SSE nudge (Story 4.4, LD-1): {@code householdId} plus the coarse {@code
 * resource} hint ({@code list}/{@code trip}, {@link HouseholdResolver#resourceFor}), never item,
 * list, receipt, or store content. Its serialized form is the privacy contract under test (see
 * {@code ChangeNudgeJsonCodecTest}) — never add a field here without updating that test.
 */
public record ChangeNudge(HouseholdId householdId, String resource) {

    public ChangeNudge {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
    }
}

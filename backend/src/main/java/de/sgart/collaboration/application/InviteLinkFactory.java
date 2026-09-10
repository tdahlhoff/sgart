package de.sgart.collaboration.application;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * Builds the canonical invite link from a single configurable base URL ({@code
 * sgart.invite.base-url}, Story 4.6, AC6) — the exact {@code <base-url>?h=<householdId>&i=<inviteId>}
 * form the OS deep link, the web fallback page, and the Flutter {@code InviteLink.tryParse} all
 * consume. The link carries only opaque UUIDs (AD-6) — no PII — so it may be logged for manual
 * dev-time testing.
 */
public class InviteLinkFactory {

    private final String baseUrl;

    public InviteLinkFactory(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    }

    public String buildLink(HouseholdId householdId, InviteId inviteId) {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        return baseUrl + "?h=" + householdId + "&i=" + inviteId;
    }
}

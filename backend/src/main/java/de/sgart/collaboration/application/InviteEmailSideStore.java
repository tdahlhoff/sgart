package de.sgart.collaboration.application;

import de.sgart.shared.InviteId;
import java.util.Optional;

/**
 * Application-owned port over the mutable {@code invite_email_side_store} table — the
 * <strong>only</strong> place a raw invite email is persisted (AD-6, locked decision 3, Story 4.1).
 * Keyed by {@link InviteId} so it is purgeable per-invite: 4.1 purges on lazy-expiry housekeeping
 * (AC5); purge-on-accept (4.2), purge-on-revoke (4.3), and purge-on-erasure (Epic 6) reuse {@link
 * #purge} unchanged. {@link #findEmail} exists for later invite-delivery (4.2/4.6), unused by 4.1's
 * own handler logic beyond store/purge.
 */
public interface InviteEmailSideStore {

    void store(InviteId inviteId, NormalizedEmail email);

    void purge(InviteId inviteId);

    Optional<NormalizedEmail> findEmail(InviteId inviteId);
}

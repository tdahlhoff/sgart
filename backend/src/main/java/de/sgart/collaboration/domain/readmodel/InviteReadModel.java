package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.event.InviteRevoked;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.shared.HouseholdId;
import java.util.Optional;

/**
 * Domain-owned port over the household's single-invite-code CQRS read model (AD-4, Story 8.4) —
 * built solely by {@code HouseholdReadModelProjector} folding {@link MemberInvited} (and ignoring
 * {@link InviteRevoked}, which the very next {@code MemberInvited} of a replace immediately
 * supersedes); a command handler never writes it. {@code GetActiveInviteCode} is the query that
 * reads through this port. Deliberately does not import {@code adapter.out} or {@code application}
 * types here — a domain port must not depend outward (AD-1/AD-2); the projector and query are named
 * in plain {@code @code} text, not {@code @link}.
 */
public interface InviteReadModel {

    /** @return the household's active invite code, or empty if the projection has not caught up yet. */
    Optional<InviteView> activeInviteOf(HouseholdId householdId);
}

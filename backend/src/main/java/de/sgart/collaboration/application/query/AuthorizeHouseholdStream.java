package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * The SSE endpoint's auth gate (Story 4.4, T1, AC3): the same Identity ACL check
 * (`ResolveMemberIdentity`) every command/query uses (AD-2, AD-5) — a live stream is just another
 * caller of it. Mirrors {@link ListHouseholdMembers}'s composition. Kept as its own tiny query
 * (rather than inlining the ACL call in the controller) so {@code adapter.in} never imports {@code
 * identity.application} directly (the convention every other controller in this context follows —
 * {@code WriteErrorAdvice}'s translation of {@link NotAMemberException} is the one sanctioned
 * exception, kept there only to map it to 403).
 */
public final class AuthorizeHouseholdStream {

    private final ResolveMemberIdentity resolveMemberIdentity;

    public AuthorizeHouseholdStream(ResolveMemberIdentity resolveMemberIdentity) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never from the path/body.
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403) — run this
     *     check <strong>before</strong> the controller returns the {@code SseEmitter}; once the
     *     response is committed at 200 a deferred 403 can no longer be sent.
     */
    public Authorization authorize(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        MemberId memberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);
        return new Authorization(householdId, memberId);
    }

    /** The resolved identity a live stream is opened for. */
    public record Authorization(HouseholdId householdId, MemberId memberId) {}
}

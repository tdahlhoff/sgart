package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.InviteEmailHasher;
import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.collaboration.application.exception.AlreadyAHouseholdMemberApplicationException;
import de.sgart.collaboration.application.exception.DuplicatePendingInviteApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidInviteEmailApplicationException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.exception.DuplicatePendingInviteException;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates {@link InvitePerson} (AC1–AC5): resolve the caller's {@link MemberId} through the
 * Identity ACL (AD-2), check the already-a-member seam (AC3, E5), let {@link Household} enforce the
 * duplicate-pending/past-TTL invariants (AC2, AC5), append, then write the raw email to the
 * side-store (AD-6). Mirrors {@link AddStoreHandler}.
 *
 * <p><strong>Append-before-side-store ordering</strong> (AC1, AC5): the side-store is written only
 * <em>after</em> a successful append. If the append itself loses a concurrency race, nothing is
 * written to either place. If the side-store write fails after a successful append, the invite
 * exists with no deliverable address — there is no retry path today, so the invite silently has
 * nothing to send; this compensation gap is tracked as deferred debt (see {@code
 * deferred-work.md}). The reverse order (side-store first) could leak a raw email for an invite
 * that never actually landed, which AD-6 forbids. Any {@link InviteExpired} raised alongside the
 * new invite (AC5, lazy housekeeping) triggers a side-store purge of the stale row in the same
 * post-append step.
 */
public final class InvitePersonHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final FindHouseholdMemberByEmail findHouseholdMemberByEmail;
    private final InviteEmailHasher inviteEmailHasher;
    private final InviteEmailSideStore inviteEmailSideStore;
    private final Clock clock;

    public InvitePersonHandler(
            EventStore eventStore,
            ResolveMemberIdentity resolveMemberIdentity,
            FindHouseholdMemberByEmail findHouseholdMemberByEmail,
            InviteEmailHasher inviteEmailHasher,
            InviteEmailSideStore inviteEmailSideStore,
            Clock clock) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.findHouseholdMemberByEmail =
                Objects.requireNonNull(findHouseholdMemberByEmail, "findHouseholdMemberByEmail must not be null");
        this.inviteEmailHasher = Objects.requireNonNull(inviteEmailHasher, "inviteEmailHasher must not be null");
        this.inviteEmailSideStore =
                Objects.requireNonNull(inviteEmailSideStore, "inviteEmailSideStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never accepted from the request body.
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidInviteEmailApplicationException if {@code rawEmail} is missing/malformed (400)
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws NotAHouseholdMemberApplicationException on an ACL/event-stream divergence (403)
     * @throws AlreadyAHouseholdMemberApplicationException if the email already belongs to a current
     *     member (409, AC3/E5)
     * @throws DuplicatePendingInviteApplicationException if a non-expired pending invite to the
     *     same email already exists (409, AC2)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawInviteId,
            String rawEmail,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId inviteId = CommandFieldTranslations.toInviteId(rawInviteId);
        NormalizedEmail normalizedEmail = NormalizedEmail.fromRaw(rawEmail);
        EmailHmac emailHmac = inviteEmailHasher.hash(normalizedEmail);

        // A non-member never reaches invitePerson — NotAMemberException propagates as a 403 (AD-2/AD-5).
        MemberId requestedBy = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        // AC3/E5: the already-a-member check happens at this seam, before the aggregate is even
        // loaded — the domain has no email to compare against (AD-6).
        findHouseholdMemberByEmail.forHousehold(normalizedEmail.value(), householdId).ifPresent(existingMember -> {
            throw new AlreadyAHouseholdMemberApplicationException(
                    "The invited email already belongs to a member of this household");
        });

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        InvitePerson command = new InvitePerson(householdId, inviteId, emailHmac, commandId, loadedVersion);
        Instant now = clock.instant();

        try {
            household.invitePerson(requestedBy, command.inviteId(), command.emailHmac(), now, command.commandId());
        } catch (DuplicatePendingInviteException duplicate) {
            throw new DuplicatePendingInviteApplicationException(duplicate.getMessage());
        } catch (NotAHouseholdMemberException notAMember) {
            throw new NotAHouseholdMemberApplicationException(notAMember.getMessage());
        }

        eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());

        // Only after a successful append: write the raw email to the side-store (AD-6), and purge
        // any stale invite's side-store row that this call's lazy housekeeping just expired (AC5).
        inviteEmailSideStore.store(inviteId, normalizedEmail);
        household.uncommittedEvents().stream()
                .filter(InviteExpired.class::isInstance)
                .map(InviteExpired.class::cast)
                .forEach(expired -> inviteEmailSideStore.purge(expired.inviteId()));
    }
}

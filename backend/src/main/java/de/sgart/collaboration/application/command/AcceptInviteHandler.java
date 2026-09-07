package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.exception.InviteAlreadyConsumedApplicationException;
import de.sgart.collaboration.application.exception.InviteExpiredApplicationException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.InviteAlreadyConsumedException;
import de.sgart.collaboration.domain.exception.InviteExpiredException;
import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.identity.application.MintMemberIdentity;
import de.sgart.identity.application.ProvisionedMemberId;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates {@link AcceptInvite} (AC1–AC5): provision the joiner's {@link MemberId} through the
 * Identity ACL <em>before</em> the aggregate can accept or reject (AD-5), let {@link Household}
 * enforce the invite state machine (AC1, AC3, AC4, AC5), persist the provisioned id only on the
 * success path, append, then purge the side-store row (AC2, AD-6). Mirrors {@link
 * InvitePersonHandler}, but accept needs no already-a-member seam and no email — the joiner's
 * identity comes entirely from their own JWT.
 *
 * <p><strong>Provision-then-persist-on-success</strong> (AD-5, DSGVO data-minimization): unlike
 * {@link CreateHouseholdHandler}, which has no post-mint domain-rejection branch, {@code
 * acceptInvite} can still throw ({@code InviteNotFound}/{@code InviteExpired}/{@code
 * InviteAlreadyConsumed}) after the joiner's id is known. Minting eagerly there would durably map a
 * rejected or stranger caller into the household — a household-access leak and a GDPR breach.
 * {@link MintMemberIdentity#provision} only computes the id (existing or a fresh unsaved one); the
 * durable mapping is written by {@link MintMemberIdentity#persist} <em>before</em> {@code append},
 * and only once the domain call above has not thrown. The lazy-expiry {@code catch} below appends
 * and purges the expiry housekeeping with no persist, so no mapping is ever written for an
 * expired/rejected/stranger caller.
 *
 * <p><strong>Persist-before-append with compensation</strong> (AD-5): persisting before {@code
 * append} keeps an append-failure recoverable via an id-stable retry (the ordering {@link
 * CreateHouseholdHandler} already relies on). But accept can still lose the {@code append} to a
 * concurrent redemption of the same bearer invite — the retry then rejects the loser (409) while
 * the mapping persisted. So a failing success-path {@code append} triggers a compensating {@link
 * MintMemberIdentity#retract}, guarded by {@link ProvisionedMemberId#freshlyProvisioned()} so an
 * existing member's real mapping is never deleted. (The fully atomic answer — a transactional
 * outbox across the JDBC mapping and the KurrentDB stream — is deferred, mirroring 4.1.)
 *
 * <p><strong>Append-before-purge ordering</strong> (AD-6): the side-store is purged only <em>after</em>
 * a successful append. An orphan side-store row (append ok, purge failed) is harmless and
 * self-corrects on a later purge point; the reverse could strand a still-pending invite's
 * deliverability.
 */
public final class AcceptInviteHandler {

    private final EventStore eventStore;
    private final MintMemberIdentity mintMemberIdentity;
    private final InviteEmailSideStore inviteEmailSideStore;
    private final Clock clock;

    public AcceptInviteHandler(
            EventStore eventStore,
            MintMemberIdentity mintMemberIdentity,
            InviteEmailSideStore inviteEmailSideStore,
            Clock clock) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.mintMemberIdentity = Objects.requireNonNull(mintMemberIdentity, "mintMemberIdentity must not be null");
        this.inviteEmailSideStore =
                Objects.requireNonNull(inviteEmailSideStore, "inviteEmailSideStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never accepted from the request body.
     * @throws de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException if the
     *     command envelope is malformed (400)
     * @throws InviteNotFoundApplicationException if the invite does not exist on the household
     *     stream (404, AC5)
     * @throws InviteExpiredApplicationException if the invite is past its TTL, lazily or already
     *     (410, AC3)
     * @throws InviteAlreadyConsumedApplicationException if the invite was already accepted by
     *     someone else (409, AC5)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawInviteId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId inviteId = CommandFieldTranslations.toInviteId(rawInviteId);

        // Provision precedes the domain call: InviteAccepted/MemberJoined must carry the joiner's
        // id (AD-5). This only computes the id (existing mapping, or a fresh *unsaved* one) — the
        // durable mapping is written by persist(), below, only once acceptInvite has not thrown.
        // freshlyProvisioned() tells us whether a compensating retract is safe if append later fails.
        ProvisionedMemberId provisioned = mintMemberIdentity.provision(keycloakUserId, householdId);
        MemberId joiner = provisioned.memberId();

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        AcceptInvite command = new AcceptInvite(householdId, inviteId, commandId, loadedVersion);
        Instant now = clock.instant();

        try {
            household.acceptInvite(command.inviteId(), joiner, now, command.commandId());
        } catch (InviteExpiredException expired) {
            // Branch-3 (lazy expiry) raised an InviteExpired before throwing; append it the way any
            // other expiry housekeeping does — no persist, so this rejected caller never gains a
            // mapping. Branch-4 (already EXPIRED) raised nothing, so there is nothing to append.
            if (!household.uncommittedEvents().isEmpty()) {
                try {
                    eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
                } catch (ConcurrencyConflictException raceLostToAnotherWriter) {
                    // A concurrent write already advanced the stream; the expiry housekeeping
                    // append lost the race. The invite is still expired for this caller — surface
                    // 410 regardless, and leave persisting the transition to whichever write landed.
                }
            }
            // Purge unconditionally: the invite is dead, so its raw email must go (AD-6), even on
            // the already-EXPIRED branch and even if an earlier expiry's purge was lost — purge is
            // idempotent, and there is no other purge point for a terminal EXPIRED invite.
            inviteEmailSideStore.purge(inviteId);
            throw new InviteExpiredApplicationException(expired.getMessage());
        } catch (InviteNotFoundException notFound) {
            throw new InviteNotFoundApplicationException(notFound.getMessage());
        } catch (InviteAlreadyConsumedException alreadyConsumed) {
            throw new InviteAlreadyConsumedApplicationException(alreadyConsumed.getMessage());
        }

        // Success path only: persist the joiner's mapping before append, so an id-stable retry
        // stays recoverable if append itself then fails (mirrors CreateHouseholdHandler's ordering).
        mintMemberIdentity.persist(keycloakUserId, householdId, joiner);

        try {
            if (!household.uncommittedEvents().isEmpty()) {
                eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
            }
        } catch (RuntimeException appendFailed) {
            // The append lost a concurrency race (or failed for any other reason) *after* persist
            // wrote the mapping. Left as-is, a stranger who lost a concurrent redemption of the same
            // bearer invite would keep durable household access despite the retry rejecting them
            // (409) — the exact F1 leak. Roll the mapping back, but only when *this* attempt freshly
            // provisioned it: an existing member's real mapping must never be deleted (AD-5).
            if (provisioned.freshlyProvisioned()) {
                mintMemberIdentity.retract(keycloakUserId, householdId);
            }
            throw appendFailed;
        }

        // Only after a successful append: purge the invite's raw-email side-store row (AC2, AD-6).
        // A no-op re-accept (no events to append) still purges idempotently.
        inviteEmailSideStore.purge(inviteId);
    }
}

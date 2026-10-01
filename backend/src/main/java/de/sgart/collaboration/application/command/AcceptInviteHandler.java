package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.ConsentGate;
import de.sgart.collaboration.application.exception.ConsentRequiredException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.application.MemberMappingConflictException;
import de.sgart.identity.application.ProvisionedMemberId;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link AcceptInvite} (Story 4.2/8.4): provision the joiner's {@link MemberId}
 * through the Identity ACL <em>before</em> the aggregate can accept or reject (AD-5), let {@link
 * Household} check the invite id against its single active code, persist the provisioned id only
 * on the success path, then append. The joiner's identity comes entirely from their own JWT.
 *
 * <p><strong>Provision-then-persist-on-success</strong> (AD-5, DSGVO data-minimization): unlike
 * {@link CreateHouseholdHandler}, which has no post-issue domain-rejection branch, {@code
 * acceptInvite} can still throw ({@code InviteNotFound} — an unknown or replaced code) after the
 * joiner's id is known. Issuing eagerly there would durably map a rejected or stranger caller into
 * the household — a household-access leak and a GDPR breach. {@link
 * IssueMemberIdentity#provision} only computes the id (existing or a fresh unsaved one); the
 * durable mapping is written by {@link IssueMemberIdentity#persist} <em>before</em> {@code append},
 * and only once the domain call above has not thrown.
 *
 * <p><strong>Persist-before-append with compensation</strong> (AD-5): persisting before {@code
 * append} keeps an append-failure recoverable via an id-stable retry (the ordering {@link
 * CreateHouseholdHandler} already relies on). But accept can still lose the {@code append} to a
 * concurrent write on the household stream — the retry then rejects the loser (409) while the
 * mapping persisted. So a failing success-path {@code append} triggers a compensating {@link
 * IssueMemberIdentity#retract}, guarded by {@link ProvisionedMemberId#freshlyProvisioned()} so an
 * existing member's real mapping is never deleted. (The fully atomic answer — a transactional
 * outbox across the JDBC mapping and the KurrentDB stream — is deferred, mirroring 4.1.)
 */
public final class AcceptInviteHandler {

    private final EventStore eventStore;
    private final IssueMemberIdentity issueMemberIdentity;
    private final ConsentGate consentGate;

    public AcceptInviteHandler(EventStore eventStore, IssueMemberIdentity issueMemberIdentity, ConsentGate consentGate) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.issueMemberIdentity = Objects.requireNonNull(issueMemberIdentity, "issueMemberIdentity must not be null");
        this.consentGate = Objects.requireNonNull(consentGate, "consentGate must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never accepted from the request body.
     * @throws de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException if the
     *     command envelope is malformed (400)
     * @throws InviteNotFoundApplicationException if {@code rawInviteId} does not match the
     *     household's active invite code — unknown, or replaced (404)
     * @throws ConsentRequiredException if the caller has no recorded consent (409 {@code
     *     consent.required}, Story 7.4 AC3, D-B) — checked first, before any state change.
     * @throws MemberMappingConflictException if the caller lost a concurrent first-time join (409
     *     {@code membership.mappingConflict}) — nothing is appended; a manual retry succeeds.
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawInviteId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        if (!consentGate.hasRecordedConsent(keycloakUserId)) {
            throw new ConsentRequiredException("Caller has not recorded consent for processing household data");
        }

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId inviteId = CommandFieldTranslations.toInviteId(rawInviteId);

        // Provision precedes the domain call: MemberJoined must carry the joiner's id (AD-5). This
        // only computes the id (existing mapping, or a fresh *unsaved* one) — the durable mapping is
        // written by persist(), below, only once acceptInvite has not thrown. freshlyProvisioned()
        // tells us whether a compensating retract is safe if append later fails.
        ProvisionedMemberId provisioned = issueMemberIdentity.provision(keycloakUserId, householdId);
        MemberId joiner = provisioned.memberId();

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        AcceptInvite command = new AcceptInvite(householdId, inviteId, commandId, loadedVersion);

        try {
            household.acceptInvite(command.inviteId(), joiner, command.commandId());
        } catch (InviteNotFoundException notFound) {
            throw new InviteNotFoundApplicationException(notFound.getMessage());
        }

        // Success path only: persist the joiner's mapping before append, so an id-stable retry
        // stays recoverable if append itself then fails (mirrors CreateHouseholdHandler's ordering).
        issueMemberIdentity.persist(keycloakUserId, householdId, joiner);

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
                issueMemberIdentity.retract(keycloakUserId, householdId);
            }
            throw appendFailed;
        }
    }
}

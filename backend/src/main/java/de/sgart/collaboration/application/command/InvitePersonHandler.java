package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.InviteLinkFactory;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates {@link InvitePerson} (Story 7.5, AC1): resolve the caller's {@link MemberId}
 * through the Identity ACL (AD-2), load the household (AD-8), let it raise {@link
 * de.sgart.collaboration.domain.event.MemberInvited}, then append. Mirrors {@link
 * AddStoreHandler}. No email is collected, hashed, or stored anywhere in this path (AD-6).
 */
public final class InvitePersonHandler {

    private static final Logger LOG = LoggerFactory.getLogger(InvitePersonHandler.class);

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final InviteLinkFactory inviteLinkFactory;
    private final Clock clock;
    private final boolean logInviteLinkForDevTesting;

    public InvitePersonHandler(
            EventStore eventStore,
            ResolveMemberIdentity resolveMemberIdentity,
            InviteLinkFactory inviteLinkFactory,
            Clock clock,
            boolean logInviteLinkForDevTesting) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.inviteLinkFactory = Objects.requireNonNull(inviteLinkFactory, "inviteLinkFactory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.logInviteLinkForDevTesting = logInviteLinkForDevTesting;
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never accepted from the request body.
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws NotAHouseholdMemberApplicationException on an ACL/event-stream divergence (403)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawInviteId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId inviteId = CommandFieldTranslations.toInviteId(rawInviteId);

        // A non-member never reaches invitePerson — NotAMemberException propagates as a 403 (AD-2/AD-5).
        MemberId requestedBy = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        InvitePerson command = new InvitePerson(householdId, inviteId, commandId, loadedVersion);
        Instant now = clock.instant();

        try {
            household.invitePerson(requestedBy, command.inviteId(), now, command.commandId());
        } catch (NotAHouseholdMemberException notAMember) {
            throw new NotAHouseholdMemberApplicationException(notAMember.getMessage());
        }

        eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());

        // The link carries only opaque UUIDs (AD-6) — no PII — so logging it is acceptable, but only
        // under the dev profile (manual end-to-end testing without SMTP); a real deployment never
        // logs invite links by default (Story 4.6, AC6/D4). Build it only when it is actually
        // logged — nothing else consumes it today (the email-delivery seam is documented, not wired).
        if (logInviteLinkForDevTesting) {
            LOG.info("Invite link for manual dev testing: {}", inviteLinkFactory.buildLink(householdId, inviteId));
        }
    }
}

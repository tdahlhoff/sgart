package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.identity.application.MintMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link CreateHousehold} (AC1, AC3): mint the creator's {@link MemberId} through the
 * Identity ACL's published port <em>before</em> the aggregate raises {@code MemberJoined} — the
 * event carries the minted id, so minting cannot happen after (the load-bearing ordering, Story
 * 1.6 Dev Notes "Mint-then-append"). Returns the new {@link HouseholdId} so the caller can route
 * straight into it without waiting for the read model to catch up (read-your-writes, AR3/NFR9).
 */
public final class CreateHouseholdHandler {

    private final EventStore eventStore;
    private final MintMemberIdentity mintMemberIdentity;

    public CreateHouseholdHandler(EventStore eventStore, MintMemberIdentity mintMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.mintMemberIdentity = Objects.requireNonNull(mintMemberIdentity, "mintMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     by the {@code adapter.in} seam — never accepted from the request body (AR10, AD-5).
     * @param rawName the caller-supplied name, not yet validated — translating a blank/over-long
     *     value into {@link InvalidHouseholdNameException} is this handler's job, so {@code
     *     adapter.in} never constructs the domain {@link HouseholdName} type itself (layering).
     * @param rawCommandId the client's command-envelope id, not yet parsed — a missing or malformed
     *     value becomes {@link InvalidCommandEnvelopeException} (a clean {@code 400}) rather than an
     *     opaque {@code 500}. The same {@code rawCommandId} on a retry converges on one household.
     * @throws InvalidHouseholdNameException if {@code rawName} fails the domain invariant
     * @throws InvalidCommandEnvelopeException if {@code rawCommandId} is missing or not a UUID
     */
    public HouseholdId handle(String keycloakUserId, String rawName, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdName name = CommandFieldTranslations.toHouseholdName(rawName);
        // Deterministic per (keycloakUserId, commandId): a retried create derives the same stream,
        // so the idempotent mint replays the existing MemberId and the append is a no-op — the
        // whole create converges on one household instead of duplicating it (Clarification 5).
        HouseholdId householdId = HouseholdId.deterministicFrom(keycloakUserId + "|" + commandId);
        AggregateVersion basedOnVersion = AggregateVersion.initial(StreamId.forHousehold(householdId));
        CreateHousehold command = new CreateHousehold(commandId, basedOnVersion, name);

        // Mint precedes append: MemberJoined must carry the ACL-minted MemberId (AD-5).
        MemberId adminMemberId = mintMemberIdentity.mint(keycloakUserId, householdId);
        Household household = Household.create(householdId, command.name(), adminMemberId, command.commandId());
        eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());

        return householdId;
    }
}

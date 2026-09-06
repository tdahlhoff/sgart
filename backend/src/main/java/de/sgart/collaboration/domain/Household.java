package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.exception.DuplicatePendingInviteException;
import de.sgart.collaboration.domain.exception.DuplicateStoreNameException;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.collaboration.domain.exception.RenameNotPermittedException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The first real aggregate (Story 1.6): a household is the top-level tenant every list, store,
 * and trip belongs to (glossary). State changes only through {@link #apply(DomainEvent)}, folding
 * {@link HouseholdCreated}, {@link MemberJoined}, and {@link HouseholdRenamed} — never mutated
 * directly by a command method (the {@link EventSourcedAggregate} contract).
 */
public final class Household extends EventSourcedAggregate {

    private HouseholdId householdId;
    private HouseholdName name;
    private final Map<MemberId, HouseholdRole> rolesByMember = new HashMap<>();
    private final Map<StoreId, StoreState> storesById = new HashMap<>();
    private final Map<InviteId, InviteState> pendingInvitesById = new HashMap<>();

    private Household(StreamId streamId) {
        super(streamId);
    }

    /**
     * Creates a brand-new household on its own stream, with {@code adminMemberId} as its creator
     * (AC1). {@code adminMemberId} must already be minted by the Identity ACL (the sole minter,
     * AD-5) — this factory never mints one itself. {@code commandId} is validated for
     * completeness of the command envelope but carries no domain meaning here; idempotency is the
     * {@code EventStore}'s concern (AD-8), not the aggregate's.
     */
    public static Household create(
            HouseholdId householdId, HouseholdName name, MemberId adminMemberId, CommandId commandId) {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(adminMemberId, "adminMemberId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        Household household = new Household(StreamId.forHousehold(householdId));
        household.raise(new HouseholdCreated(EventId.generate(), householdId, name));
        household.raise(new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN));
        return household;
    }

    /** Rebuilds a household from its persisted event history (empty history for an unseen stream). */
    public static Household rehydrate(StreamId streamId, List<? extends DomainEvent> history) {
        Household household = new Household(streamId);
        household.replay(history);
        return household;
    }

    public HouseholdId householdId() {
        return householdId;
    }

    public HouseholdName name() {
        return name;
    }

    /**
     * Renames the household (AC3) — an <strong>Admin-only</strong> capability enforced here as a
     * domain invariant, not merely hidden in the UI (AC4): {@code requestedBy} must map to an
     * {@link HouseholdRole#ADMIN} role recorded by a prior {@link MemberJoined}, otherwise a
     * {@link RenameNotPermittedException} is raised (an unknown member is likewise rejected). A
     * rename to the current name is a convergent no-op — it raises nothing (AD-8) — so an empty
     * {@link HouseholdRenamed} never reaches the stream.
     *
     * @param commandId validated for completeness of the command envelope (AD-8) but with no domain
     *     meaning here; idempotency is the {@code EventStore}'s concern, not the aggregate's
     */
    public void rename(MemberId requestedBy, HouseholdName newName, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (rolesByMember.get(requestedBy) != HouseholdRole.ADMIN) {
            throw new RenameNotPermittedException(
                    "Only an Admin of the household may rename it");
        }
        if (newName.equals(this.name)) {
            return; // convergent no-op — the name is already what the caller asked for (AD-8)
        }
        raise(new HouseholdRenamed(EventId.generate(), householdId, newName));
    }

    /**
     * Adds a store to the household as an entity of this aggregate (AC1, AD-10) — only the household
     * root accepts the command. Unlike {@link #rename}, this is <strong>membership-gated, not
     * role-gated</strong>: any member may add a store ("Any Member", AC1), so {@code requestedBy}
     * need only be a known member, not an Admin.
     *
     * <p>The name must be unique among <em>active</em> (non-archived) stores, compared
     * case-insensitively and trimmed ({@link StoreName} already trims) — so re-adding a name after
     * its store was archived is allowed (AC3). A duplicate raises {@link
     * DuplicateStoreNameException}. {@code chainId} is the optional client-decided chain suggestion
     * (AC2); {@code null} leaves the store unlinked.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here;
     *     idempotency is the {@code EventStore}'s concern, not the aggregate's
     */
    public void addStore(
            MemberId requestedBy, StoreId storeId, StoreName name, StoreChainId chainId, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireMember(requestedBy);

        if (hasActiveStoreNamed(name)) {
            throw new DuplicateStoreNameException(
                    "A store named '" + name.value() + "' already exists in this household");
        }
        raise(new StoreAdded(EventId.generate(), householdId, storeId, name, chainId));
    }

    /**
     * Archives a store (AC3) — a soft state change that hides it from future selection without
     * deleting it or any historical trip/assignment that referenced it (FR3). Membership-gated, not
     * role-gated (AC1), like {@link #addStore}. Archiving an <em>already-archived or unknown</em>
     * store raises nothing (convergent no-op, AD-8), mirroring {@link #rename}'s no-op branch.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void archiveStore(MemberId requestedBy, StoreId storeId, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireMember(requestedBy);

        StoreState store = storesById.get(storeId);
        if (store == null || store.archived()) {
            return; // convergent no-op — nothing to archive (AD-8)
        }
        raise(new StoreArchived(EventId.generate(), householdId, storeId));
    }

    /**
     * Invites a person by email (Story 4.1, AC1/AC2/AC4/AC5) — membership-gated, not role-gated, like
     * {@link #addStore}: any member may invite ({@code requireMember}), never Admin-only. The
     * invitee's already-a-member check (AC3, E5) happens at the application/ACL seam <em>before</em>
     * this is called — the aggregate has no way to see an email (AD-6) and so cannot enforce it.
     *
     * <p>A <strong>non-expired</strong> pending invite to the same {@code emailHmac} is rejected
     * ({@link DuplicatePendingInviteException}, AC2) — deliberately not a convergent no-op (AD-8,
     * §3.4). A <strong>past-TTL</strong> pending invite to the same email is the one blocker lazy
     * housekeeping clears: {@link InviteExpired} is raised for it first (AC5), then the new invite
     * proceeds. {@code now} is caller-injected (never {@code Instant.now()} here) so expiry stays
     * deterministic and testable.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void invitePerson(
            MemberId requestedBy, InviteId inviteId, EmailHmac emailHmac, Instant now, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(emailHmac, "emailHmac must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireMember(requestedBy);

        for (Map.Entry<InviteId, InviteState> entry : pendingInvitesById.entrySet()) {
            InviteState invite = entry.getValue();
            if (invite.status() != InviteStatus.PENDING || !invite.emailHmac().equals(emailHmac)) {
                continue;
            }
            if (invite.isExpiredAt(now)) {
                raise(new InviteExpired(EventId.generate(), householdId, entry.getKey()));
            } else {
                throw new DuplicatePendingInviteException(
                        "A pending invite to this email already exists in this household");
            }
        }

        raise(new MemberInvited(
                EventId.generate(), householdId, inviteId, emailHmac, requestedBy, HouseholdRole.PARTICIPANT, now));
    }

    private void requireMember(MemberId requestedBy) {
        if (!rolesByMember.containsKey(requestedBy)) {
            throw new NotAHouseholdMemberException(
                    "Only a member of the household may manage its stores");
        }
    }

    private boolean hasActiveStoreNamed(StoreName name) {
        String candidate = name.value().toLowerCase(Locale.ROOT);
        return storesById.values().stream()
                .anyMatch(store -> !store.archived() && store.name().value().toLowerCase(Locale.ROOT).equals(candidate));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case HouseholdCreated created -> {
                this.householdId = created.householdId();
                this.name = created.name();
            }
            case MemberJoined joined -> {
                // Records the member's role so the aggregate can enforce role-scoped invariants —
                // the first of which is Story 1.7's Admin-only rename (AC4). The Identity ACL still
                // owns the keycloak↔member mapping (AD-5); this is only the role, keyed by the
                // pseudonymous MemberId (no PII, AD-6).
                rolesByMember.put(joined.memberId(), joined.role());
            }
            case HouseholdRenamed renamed -> this.name = renamed.newName();
            case StoreAdded added ->
                storesById.put(added.storeId(), new StoreState(added.name(), added.chainId(), false));
            case StoreArchived archived -> {
                StoreState existing = storesById.get(archived.storeId());
                if (existing != null) {
                    storesById.put(archived.storeId(), existing.archived(true));
                }
            }
            case MemberInvited invited ->
                pendingInvitesById.put(
                        invited.inviteId(),
                        new InviteState(invited.emailHmac(), invited.invitedAt(), InviteStatus.PENDING));
            case InviteExpired expired -> {
                InviteState existing = pendingInvitesById.get(expired.inviteId());
                if (existing != null) {
                    pendingInvitesById.put(expired.inviteId(), existing.withStatus(InviteStatus.EXPIRED));
                }
            }
            default -> throw new IllegalArgumentException(
                    "Household cannot apply unknown event type: " + event.getClass());
        }
    }

    /**
     * A store as held inside the {@link Household} aggregate (AD-10) — the folded state the
     * invariants read (active-name uniqueness, no-op archive). Not the read model; that is projected
     * separately (AD-4).
     */
    private record StoreState(StoreName name, StoreChainId chainId, boolean archived) {

        StoreState archived(boolean archived) {
            return new StoreState(name, chainId, archived);
        }
    }

    /** Status a folded invite carries — kept foldable/out of the active-blocker set once expired. */
    private enum InviteStatus {
        PENDING,
        EXPIRED
    }

    /**
     * A pending (or lazily expired) invite as held inside the {@link Household} aggregate (AD-10):
     * the folded state {@link #invitePerson} reads for the duplicate-pending / past-TTL invariants
     * (AC2, AC5). Mirrors {@link StoreState}. {@code invitedAt} plus {@link Invite#TIME_TO_LIVE}
     * decides expiry deterministically — never wall-clock time read here.
     */
    private record InviteState(EmailHmac emailHmac, Instant invitedAt, InviteStatus status) {

        boolean isExpiredAt(Instant now) {
            return invitedAt.plus(Invite.TIME_TO_LIVE).isBefore(now) || invitedAt.plus(Invite.TIME_TO_LIVE).equals(now);
        }

        InviteState withStatus(InviteStatus status) {
            return new InviteState(emailHmac, invitedAt, status);
        }
    }
}

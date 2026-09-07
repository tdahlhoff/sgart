package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.InviteAccepted;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.InviteRevoked;
import de.sgart.collaboration.domain.event.MemberDemoted;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberPromoted;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.exception.DuplicatePendingInviteException;
import de.sgart.collaboration.domain.exception.DuplicateStoreNameException;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.collaboration.domain.exception.InviteAlreadyConsumedException;
import de.sgart.collaboration.domain.exception.InviteExpiredException;
import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.collaboration.domain.exception.LastAdminException;
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
 * {@link HouseholdCreated}, {@link MemberJoined}, {@link HouseholdRenamed}, {@link MemberInvited},
 * {@link InviteExpired}, {@link InviteAccepted}, {@link InviteRevoked}, {@link MemberLeft}, {@link
 * MemberRemoved}, {@link MemberPromoted}, {@link MemberDemoted}, and {@link HouseholdDeleted} —
 * never mutated directly by a command method (the {@link EventSourcedAggregate} contract).
 */
public final class Household extends EventSourcedAggregate {

    private HouseholdId householdId;
    private HouseholdName name;
    private boolean deleted;
    private final Map<MemberId, HouseholdRole> rolesByMember = new HashMap<>();
    private final Map<StoreId, StoreState> storesById = new HashMap<>();
    private final Map<InviteId, InviteState> pendingInvitesById = new HashMap<>();

    private Household(StreamId streamId) {
        super(streamId);
    }

    /**
     * Creates a brand-new household on its own stream, with {@code adminMemberId} as its creator
     * (AC1). {@code adminMemberId} must already be issued by the Identity ACL (the sole issuer,
     * AD-5) — this factory never issues one itself. {@code commandId} is validated for
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
     * Whether {@code memberId} currently holds a role in this household, per the folded event
     * history — used by the governance handlers to self-heal a stranded ACL mapping (retract is
     * caller-independent) without requiring a fresh authorization check.
     */
    public boolean isMember(MemberId memberId) {
        return rolesByMember.containsKey(memberId);
    }

    /** Whether this household has been deleted, per the folded event history. */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * The ids of every currently-{@code PENDING} invite, per the folded event history — used by
     * {@link de.sgart.collaboration.application.command.DeleteHouseholdHandler} to purge their raw
     * email rows from the {@link de.sgart.collaboration.application.InviteEmailSideStore} (AD-6):
     * revoke and accept already purge on their own invite; a household delete must purge every
     * invite still pending, or its raw email survives, undiscoverable once the read model is gone.
     */
    public List<InviteId> pendingInviteIds() {
        return pendingInvitesById.entrySet().stream()
                .filter(entry -> entry.getValue().status() == InviteStatus.PENDING)
                .map(Map.Entry::getKey)
                .toList();
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

    /**
     * Redeems a personal invite (Story 4.2, AC1, AC3, AC4, AC5) — the one command with
     * <strong>no membership gate</strong>: accept is precisely how a non-member becomes one, unlike
     * {@link #invitePerson}'s {@code requireMember}. {@code joiner} is the Identity-ACL-issued
     * {@link MemberId} for the accepting caller (AD-5); {@code now} is caller-injected, never {@code
     * Instant.now()} here, so expiry stays deterministic and testable.
     *
     * <p>Branches on the folded invite state for {@code inviteId}:
     * <ol>
     *   <li>absent → {@link InviteNotFoundException} (no event);</li>
     *   <li>{@code PENDING} and not expired at {@code now} → raises {@link InviteAccepted}, and —
     *       unless {@code joiner} is already a member (AC4, E5) — also raises {@link MemberJoined}
     *       as {@link HouseholdRole#PARTICIPANT};</li>
     *   <li>{@code PENDING} and expired at {@code now} → raises the lazy {@link InviteExpired}
     *       transition, then throws {@link InviteExpiredException} (AC3);</li>
     *   <li>{@code EXPIRED} → throws {@link InviteExpiredException} (no new event);</li>
     *   <li>{@code ACCEPTED} → a no-op success (raises nothing) if {@code joiner} is already a
     *       member (the convergent re-accept, AD-8/§3.5), otherwise throws {@link
     *       InviteAlreadyConsumedException} (AC5) — a spent link cannot be ridden by a stranger.</li>
     * </ol>
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void acceptInvite(InviteId inviteId, MemberId joiner, Instant now, CommandId commandId) {
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(joiner, "joiner must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        InviteState invite = pendingInvitesById.get(inviteId);
        if (invite == null) {
            throw new InviteNotFoundException("No invite " + inviteId + " exists in this household");
        }

        switch (invite.status()) {
            case PENDING -> {
                if (invite.isExpiredAt(now)) {
                    raise(new InviteExpired(EventId.generate(), householdId, inviteId));
                    throw new InviteExpiredException("Invite " + inviteId + " has expired");
                }
                raise(new InviteAccepted(EventId.generate(), householdId, inviteId, joiner));
                if (!rolesByMember.containsKey(joiner)) {
                    raise(new MemberJoined(EventId.generate(), householdId, joiner, HouseholdRole.PARTICIPANT));
                }
            }
            case EXPIRED -> throw new InviteExpiredException("Invite " + inviteId + " has expired");
            case ACCEPTED -> {
                if (!rolesByMember.containsKey(joiner)) {
                    throw new InviteAlreadyConsumedException(
                            "Invite " + inviteId + " was already accepted by someone else");
                }
                // convergent no-op — the same joiner re-accepting an already-consumed invite (AD-8)
            }
            default ->
                throw new IllegalStateException("Unhandled invite status " + invite.status());
        }
    }

    /**
     * A member leaves their household voluntarily (Story 4.3, AC3, AC5) — no membership gate on
     * <em>who</em> can leave (anyone leaving is by definition self-service), only on whether they
     * are currently a member. Leaving when not a member is a convergent no-op (§3.5). The household's
     * last Admin may not leave ({@link LastAdminException}, AC5) — the invariant is guarded here,
     * atomically, off the folded {@code rolesByMember} map.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void leaveHousehold(MemberId requestedBy, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireNotDeleted();

        if (!rolesByMember.containsKey(requestedBy)) {
            return; // convergent no-op — not a member, nothing to leave (§3.5)
        }
        if (isOnlyAdmin(requestedBy)) {
            throw new LastAdminException("The last Admin of a household may not leave it");
        }
        raise(new MemberLeft(EventId.generate(), householdId, requestedBy));
    }

    /**
     * An Admin removes <strong>another</strong> member from the household (Story 4.3, AC2, AC4) —
     * Admin-only governance. A self-target is always rejected ({@link GovernanceNotPermittedException})
     * — a voluntary departure must go through {@link #leaveHousehold}, so it always emits {@code
     * MemberLeft}, never a self-inflicted {@code MemberRemoved}. Because a self-target is blocked
     * before this point and {@code requestedBy} must already be an Admin, the target of a genuine
     * removal can never be the household's sole Admin — the last-Admin invariant does not apply here
     * (it does for {@link #leaveHousehold} and {@link #demoteMember}). Removing a non-member is a
     * convergent no-op (§3.5).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void removeMember(MemberId requestedBy, MemberId target, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireNotDeleted();
        requireAdmin(requestedBy);

        if (target.equals(requestedBy)) {
            throw new GovernanceNotPermittedException(
                    "An Admin may not remove themselves; leave the household instead");
        }
        if (!rolesByMember.containsKey(target)) {
            return; // convergent no-op — target is not a member (§3.5)
        }
        raise(new MemberRemoved(EventId.generate(), householdId, target, requestedBy));
    }

    /**
     * An Admin promotes a Participant to Admin (Story 4.3, AC2, AC4). Promoting an already-Admin is
     * a convergent no-op (§3.5); an unknown target throws {@link NotAHouseholdMemberException}.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void promoteMember(MemberId requestedBy, MemberId target, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireNotDeleted();
        requireAdmin(requestedBy);

        HouseholdRole currentRole = rolesByMember.get(target);
        if (currentRole == null) {
            throw new NotAHouseholdMemberException("Cannot promote a caller who is not a member of the household");
        }
        if (currentRole == HouseholdRole.ADMIN) {
            return; // convergent no-op — already an Admin (§3.5)
        }
        raise(new MemberPromoted(EventId.generate(), householdId, target, requestedBy));
    }

    /**
     * An Admin demotes another Admin to Participant (Story 4.3, AC2, AC4, AC5). Demoting an
     * already-Participant is a convergent no-op (§3.5); an unknown target throws {@link
     * NotAHouseholdMemberException}; demoting the household's last Admin is prevented ({@link
     * LastAdminException}, AC5).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void demoteMember(MemberId requestedBy, MemberId target, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireNotDeleted();
        requireAdmin(requestedBy);

        HouseholdRole currentRole = rolesByMember.get(target);
        if (currentRole == null) {
            throw new NotAHouseholdMemberException("Cannot demote a caller who is not a member of the household");
        }
        if (currentRole == HouseholdRole.PARTICIPANT) {
            return; // convergent no-op — already a Participant (§3.5)
        }
        if (isOnlyAdmin(target)) {
            throw new LastAdminException("The last Admin of a household may not be demoted");
        }
        raise(new MemberDemoted(EventId.generate(), householdId, target, requestedBy));
    }

    /**
     * An Admin revokes a pending invite (Story 4.3, AC2, AC6), completing its lifecycle ({@code
     * PENDING -> REVOKED}). Branches on the folded invite state: absent or a terminal
     * non-{@code PENDING} state other than {@code REVOKED} (i.e. {@code ACCEPTED}/{@code EXPIRED}) is
     * rejected as "no pending invite to revoke" ({@link InviteNotFoundException}); an already-{@code
     * REVOKED} invite is a convergent no-op (§3.5). No expiry check — a past-TTL {@code PENDING}
     * invite may still be revoked (revoke is a terminal governance action; expiry is lazy
     * housekeeping elsewhere).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void revokeInvite(MemberId requestedBy, InviteId inviteId, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireNotDeleted();
        requireAdmin(requestedBy);

        InviteState invite = pendingInvitesById.get(inviteId);
        if (invite == null) {
            throw new InviteNotFoundException("No invite " + inviteId + " exists in this household");
        }

        switch (invite.status()) {
            case PENDING -> raise(new InviteRevoked(EventId.generate(), householdId, inviteId, requestedBy));
            case REVOKED -> { /* convergent no-op — already revoked (§3.5) */ }
            case ACCEPTED, EXPIRED ->
                throw new InviteNotFoundException("Invite " + inviteId + " has no pending invite to revoke");
        }
    }

    /**
     * An Admin deletes the household (Story 4.3, AC2, AC7) — no last-Admin guard, unlike leave/
     * remove/demote: deleting the whole household is allowed even for a sole Admin. Deleting an
     * already-deleted household is a convergent no-op (§3.5).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void deleteHousehold(MemberId requestedBy, CommandId commandId) {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (deleted) {
            return; // convergent no-op — already deleted (§3.5), checked before requireAdmin so a
            // re-delete retry stays convergent even for a caller whose role has since changed
        }
        requireAdmin(requestedBy);
        raise(new HouseholdDeleted(EventId.generate(), householdId, requestedBy));
    }

    private void requireAdmin(MemberId requestedBy) {
        if (rolesByMember.get(requestedBy) != HouseholdRole.ADMIN) {
            throw new GovernanceNotPermittedException(
                    "Only an Admin of the household may perform this governance action");
        }
    }

    private boolean isOnlyAdmin(MemberId memberId) {
        if (rolesByMember.get(memberId) != HouseholdRole.ADMIN) {
            return false;
        }
        return rolesByMember.values().stream().filter(role -> role == HouseholdRole.ADMIN).count() == 1;
    }

    /**
     * Defense-in-depth guard against mutating an already-deleted household (Story 4.3, T10) — largely
     * unreachable in practice because the ACL mappings are already de-linked by the time this would
     * be called (a deleted household's members 403 at the ACL seam first), but a real domain
     * invariant nonetheless.
     */
    private void requireNotDeleted() {
        if (deleted) {
            throw new GovernanceNotPermittedException("This household has been deleted");
        }
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
            case InviteAccepted accepted -> {
                InviteState existing = pendingInvitesById.get(accepted.inviteId());
                if (existing != null) {
                    pendingInvitesById.put(accepted.inviteId(), existing.withStatus(InviteStatus.ACCEPTED));
                }
            }
            case InviteRevoked revoked -> {
                InviteState existing = pendingInvitesById.get(revoked.inviteId());
                if (existing != null) {
                    pendingInvitesById.put(revoked.inviteId(), existing.withStatus(InviteStatus.REVOKED));
                }
            }
            case MemberLeft left -> rolesByMember.remove(left.memberId());
            case MemberRemoved removed -> rolesByMember.remove(removed.memberId());
            case MemberPromoted promoted -> rolesByMember.put(promoted.memberId(), HouseholdRole.ADMIN);
            case MemberDemoted demoted -> rolesByMember.put(demoted.memberId(), HouseholdRole.PARTICIPANT);
            case HouseholdDeleted ignored -> this.deleted = true;
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

    /** Status a folded invite carries — kept foldable/out of the active-blocker set once expired or
     * accepted (Story 4.2). */
    private enum InviteStatus {
        PENDING,
        EXPIRED,
        ACCEPTED,
        REVOKED
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

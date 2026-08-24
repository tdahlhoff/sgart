package de.sgart.collaboration.domain;

import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.HashMap;
import java.util.List;
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
            default -> throw new IllegalArgumentException(
                    "Household cannot apply unknown event type: " + event.getClass());
        }
    }
}

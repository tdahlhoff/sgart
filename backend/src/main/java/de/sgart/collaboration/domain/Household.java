package de.sgart.collaboration.domain;

import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;

/**
 * The first real aggregate (Story 1.6): a household is the top-level tenant every list, store,
 * and trip belongs to (glossary). State changes only through {@link #apply(DomainEvent)}, folding
 * {@link HouseholdCreated} and {@link MemberJoined} — never mutated directly by a command method
 * (the {@link EventSourcedAggregate} contract).
 */
public final class Household extends EventSourcedAggregate {

    private HouseholdId householdId;
    private HouseholdName name;

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

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case HouseholdCreated created -> {
                this.householdId = created.householdId();
                this.name = created.name();
            }
            case MemberJoined joined -> {
                // The creator's membership is recorded by the Identity ACL mapping (Task 2), not
                // tracked as aggregate state here — no household-scoped member list is needed
                // until Epic 4's governance work (YAGNI).
            }
            default -> throw new IllegalArgumentException(
                    "Household cannot apply unknown event type: " + event.getClass());
        }
    }
}

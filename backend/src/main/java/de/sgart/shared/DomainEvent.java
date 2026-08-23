package de.sgart.shared;

/**
 * A past-tense fact an aggregate emitted — the sole output of a state change (AD-1). Appended to the
 * aggregate's event store stream and later folded back to rehydrate the aggregate and to build read
 * models.
 *
 * <p>Concrete events are PascalCase and past-tense (e.g. {@code HouseholdRegistered},
 * {@code ItemPostponed}) and live in a context's {@code ..domain..} package (AR10). The contract is
 * deliberately minimal (YAGNI): it exposes only the {@link EventId}. Later events add their own
 * fields; occurredOn, member attribution, or sequence metadata are introduced when a concrete event
 * actually needs them, not speculatively here.
 */
public interface DomainEvent {

    EventId eventId();
}

package de.sgart.collaboration.domain;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * A store was added to a household (Story 1.8, AC1). {@code Store} is an entity <em>inside</em> the
 * {@link Household} aggregate (AD-10), so this event lives on the household stream ({@code
 * household-{id}}) — there is no separate store stream.
 *
 * <p>{@code chainId} is the optional accepted chain suggestion (AC2): a nullable opaque
 * {@link StoreChainId} the client decided from its cached reference list. It is never validated
 * server-side against the Store Reference context (advisory / client-decided), and its absence
 * ({@code null}) means the store is unlinked. Carries no personal data — a shop name and an opaque
 * chain id, never a person (AD-5/AD-6).
 */
public record StoreAdded(
        EventId eventId, HouseholdId householdId, StoreId storeId, StoreName name, StoreChainId chainId)
        implements DomainEvent {

    public StoreAdded {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        // chainId is intentionally nullable — an unlinked store has no accepted chain (AC2).
    }
}

package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a household from an event-store stream key, and the coarse {@code resource} hint from
 * the stream key + event-type string — both <strong>without ever deserializing the event body</strong>
 * (Story 4.4, T4/T5). A {@code household-{id}} stream carries its {@link HouseholdId} directly in
 * the key; {@code list-}/{@code trip-} streams need one read-model lookup, which is then cached
 * (the mapping is immutable once created — a list/trip never moves household).
 *
 * <p>Rejected alternative: a {@code HouseholdScopedEvent} marker interface implemented across the
 * ~27 event classes that carry the field. Rejected as speculative infrastructure (YAGNI) that would
 * force this codec-free nudge path to deserialize personal-data payloads for no functional gain
 * (§5) — see the story's Dev Notes "Plan refinements" for the full rationale.
 */
final class HouseholdResolver {

    private static final String HOUSEHOLD_PREFIX = StreamId.StreamType.HOUSEHOLD.prefix() + "-";
    private static final String LIST_PREFIX = StreamId.StreamType.LIST.prefix() + "-";
    private static final String TRIP_PREFIX = StreamId.StreamType.TRIP.prefix() + "-";

    private final ShoppingListReadModel shoppingListReadModel;
    private final TripStoreReadModel tripStoreReadModel;
    private final ConcurrentMap<UUID, HouseholdId> listHouseholdCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, HouseholdId> tripHouseholdCache = new ConcurrentHashMap<>();

    HouseholdResolver(ShoppingListReadModel shoppingListReadModel, TripStoreReadModel tripStoreReadModel) {
        this.shoppingListReadModel =
                Objects.requireNonNull(shoppingListReadModel, "shoppingListReadModel must not be null");
        this.tripStoreReadModel = Objects.requireNonNull(tripStoreReadModel, "tripStoreReadModel must not be null");
    }

    /**
     * @return the household the stream belongs to, or empty when the stream key doesn't match a
     *     known prefix, or (for {@code list-}/{@code trip-}) the row isn't projected yet — a
     *     projector-race edge the caller skips rather than fails on.
     */
    Optional<HouseholdId> resolveHouseholdId(String streamId) {
        if (streamId.startsWith(HOUSEHOLD_PREFIX)) {
            return Optional.of(new HouseholdId(UUID.fromString(streamId.substring(HOUSEHOLD_PREFIX.length()))));
        }
        if (streamId.startsWith(LIST_PREFIX)) {
            UUID listId = UUID.fromString(streamId.substring(LIST_PREFIX.length()));
            HouseholdId cached = listHouseholdCache.get(listId);
            if (cached != null) {
                return Optional.of(cached);
            }
            Optional<HouseholdId> resolved = shoppingListReadModel.householdIdOfList(new ShoppingListId(listId));
            resolved.ifPresent(householdId -> listHouseholdCache.put(listId, householdId));
            return resolved;
        }
        if (streamId.startsWith(TRIP_PREFIX)) {
            UUID tripId = UUID.fromString(streamId.substring(TRIP_PREFIX.length()));
            HouseholdId cached = tripHouseholdCache.get(tripId);
            if (cached != null) {
                return Optional.of(cached);
            }
            Optional<HouseholdId> resolved = tripStoreReadModel.householdIdOfTrip(new TripId(tripId));
            resolved.ifPresent(householdId -> tripHouseholdCache.put(tripId, householdId));
            return resolved;
        }
        return Optional.empty();
    }

    /**
     * The coarse client hint (LD-1) — derived from the stream key + event-type <em>string</em>
     * alone, never the decoded body.
     */
    String resourceFor(String streamId, String eventType) {
        if (streamId.startsWith(LIST_PREFIX)) {
            return "list";
        }
        if (streamId.startsWith(TRIP_PREFIX)) {
            return "trip";
        }
        return eventType.startsWith("Member") || eventType.startsWith("Invite") ? "members" : "household";
    }
}

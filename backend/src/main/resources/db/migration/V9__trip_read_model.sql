-- Trip read side (CQRS, AD-4, Story 3.2): two additive changes, no backfill, no rewrite of V5-V8.
-- No trip_read_model header table — household_id/list_id/status are all derivable via the list's
-- active_trip_id (a header would duplicate them, KISS/YAGNI, Cl. 4).

-- The list's currently-active trip (Story 3.2) — set by the list projector on TripStartedForList,
-- cleared on completion (Story 3.4). The 1:1 navigation key list->trip; a REFERENCE to the
-- ShoppingTrip aggregate (AR2), not FK-constrained. No personal data — a trip id is household
-- content, not a person (AC5).
ALTER TABLE shopping_list_read_model ADD COLUMN active_trip_id UUID NULL;

-- The trip's store set in add order (Story 3.2), projected from TripStarted/StoreAddedToTrip on
-- trip-{id}. sequence_number gives the group order. No personal data.
CREATE TABLE trip_store_read_model (
    trip_id         UUID NOT NULL,
    store_id        UUID NOT NULL,
    sequence_number BIGSERIAL,
    PRIMARY KEY (trip_id, store_id)
);

CREATE INDEX idx_trip_store_read_model_trip ON trip_store_read_model (trip_id);

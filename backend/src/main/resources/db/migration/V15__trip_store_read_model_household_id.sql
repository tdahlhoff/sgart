-- Adds household_id to trip_store_read_model (Story 4.3, AC7, decision 4): needed solely so the
-- household-delete cascade can purge a household's trip-store rows directly. V9's design
-- deliberately avoided this column (derivable via the list's active_trip_id) — but that
-- derivation breaks once shopping_list_read_model's own delete-cascade purge has already run (the
-- two purges are independent, unordered projections), so the trip-store rows need their own
-- household_id to stay purgeable on their own.
ALTER TABLE trip_store_read_model ADD COLUMN household_id UUID NULL;

-- Backfill any pre-existing rows from the still-derivable link at migration time: the list that
-- currently has this trip active. Only reaches a trip still active on its list — SGART has no
-- production data yet (pre-beta), so no completed trip's rows are expected to need backfilling.
UPDATE trip_store_read_model tsr
SET household_id = sl.household_id
FROM shopping_list_read_model sl
WHERE sl.active_trip_id = tsr.trip_id;

ALTER TABLE trip_store_read_model ALTER COLUMN household_id SET NOT NULL;

CREATE INDEX idx_trip_store_read_model_household ON trip_store_read_model (household_id);

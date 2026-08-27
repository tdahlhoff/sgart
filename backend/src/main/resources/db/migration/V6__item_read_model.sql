-- Item read model (CQRS, AD-4): projected from ItemAdded/ItemUpdated/ItemRemoved on the owning
-- list's list-{id} stream (Story 2.3, AC1/AC3/AC4). No personal data — an item's name/note/quantity
-- is not personal data, like a shopping list name (AC9); the table records no creator/member
-- attribution. household_id is denormalised (not on the list-{id} stream key) so ListItems can
-- filter by (household_id, list_id) in one query and Epic-6 erasure can locate rows by household.
-- sequence_number is a monotonic, never-reused ordering key (BIGSERIAL): creation order is derived
-- from it on read, never stored elsewhere. An upsert on re-projection never rewrites it, so the
-- creation order stays stable across replays.
CREATE TABLE item_read_model (
    item_id         UUID           PRIMARY KEY,
    list_id         UUID           NOT NULL,
    household_id    UUID           NOT NULL,
    name            VARCHAR(120)   NOT NULL,
    note            VARCHAR(240)   NULL,
    quantity_amount NUMERIC        NOT NULL,
    quantity_unit   VARCHAR(20)    NOT NULL,
    sequence_number BIGSERIAL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_item_read_model_list ON item_read_model (list_id);

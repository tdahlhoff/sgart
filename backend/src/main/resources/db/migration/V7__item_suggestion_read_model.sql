-- Item suggestion read model (CQRS, AD-4, Story 2.5): a SEPARATE history-surviving projection from
-- item_read_model (V6). item_read_model is *current items* — its rows are deleted on ItemRemoved and
-- on the source side of ItemMovedToList (Story 2.4). FR6 needs the household's whole PAST item
-- names for autocomplete, so this table is append-/upsert-only: ItemAdded/ItemUpdated record usage,
-- ItemRemoved/ItemMovedToList record nothing (Cl. 1). One row per distinct name per household,
-- holding that name's last-used display casing + attributes (upsert on (household_id,
-- normalized_name) — last write wins, Cl. 6). No personal data — an item's name/note/quantity is
-- household content, not a person (AD-5/AD-6, mirrors item_read_model AC9); household_id is carried
-- so Epic-6 erasure can locate rows by household.
CREATE TABLE item_suggestion_read_model (
    household_id    UUID           NOT NULL,
    normalized_name VARCHAR(120)   NOT NULL,
    name            VARCHAR(120)   NOT NULL,
    note            VARCHAR(240)   NULL,
    quantity_amount NUMERIC        NOT NULL,
    quantity_unit   VARCHAR(20)    NOT NULL,
    PRIMARY KEY (household_id, normalized_name)
);

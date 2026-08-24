-- Store read model (CQRS, AD-4): projected from StoreAdded/StoreArchived on the household stream.
-- Household-scoped; no personal data — a shop name and an opaque advisory chain id, not a person
-- (Story 1.8, AC1). Covered by the household-erasure read-model scrub (AD-7). chain_id is the
-- optional accepted chain suggestion (AC2) and is never validated against store_chain_reference
-- (advisory / client-decided). archived is a soft-remove flag — a removed store is hidden from
-- future selection but never row-deleted, so historical trips/assignments keep their record (AC3).
CREATE TABLE store_read_model (
    household_id UUID         NOT NULL,
    store_id     UUID         NOT NULL,
    name         VARCHAR(120) NOT NULL,
    chain_id     UUID         NULL,
    archived     BOOLEAN      NOT NULL DEFAULT false,
    PRIMARY KEY (household_id, store_id)
);

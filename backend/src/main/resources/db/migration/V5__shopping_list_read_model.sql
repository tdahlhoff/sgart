-- Shopping list read model (CQRS, AD-4): projected from ShoppingListCreated/ShoppingListRenamed on
-- each list's own list-{id} stream (Story 2.1, AC1/AC2). No personal data — a list name is not
-- personal data, like a household name (AC1); the table records no creator/member attribution.
-- sequence_number is a monotonic, never-reused ordering key (BIGSERIAL): the AC2 "Liste N" ordinal
-- is derived from it on read, never stored. An upsert on re-projection never rewrites it, so the
-- creation order stays stable across replays.
CREATE TABLE shopping_list_read_model (
    list_id         UUID         PRIMARY KEY,
    household_id    UUID         NOT NULL,
    name            VARCHAR(120) NULL,
    status          VARCHAR(20)  NOT NULL,
    sequence_number BIGSERIAL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_shopping_list_read_model_household ON shopping_list_read_model (household_id);

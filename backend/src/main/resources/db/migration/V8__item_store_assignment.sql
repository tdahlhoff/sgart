-- Item store assignment (CQRS, AD-4, Story 2.6): two additive ALTERs, no backfill, no rewrite of
-- V6/V7. Existing rows read as unassigned.

-- The item's assigned store (Story 2.6, AC1) — nullable = unassigned ("+ Geschäft"). A REFERENCE
-- to a Store entity in the separate Household aggregate (AR2), not FK-constrained: Store is a
-- different aggregate this event never validates against (Cl. 1), and an archived store must stay
-- referenced so a historical row keeps its record (Story 1.8 E6, AC4). No personal data — a store
-- id is household content, not a person (AC7).
ALTER TABLE item_read_model ADD COLUMN store_id UUID NULL;

-- The name's last-used store (Story 2.6, AC6 — the Story 2.5 Cl. 4 deferral). Nullable = never
-- assigned. Upsert last-wins, written only by ItemAssignedToStore's projection; untouched by the
-- add/update recordUsage upsert (Cl. 7).
ALTER TABLE item_suggestion_read_model ADD COLUMN default_store_id UUID NULL;

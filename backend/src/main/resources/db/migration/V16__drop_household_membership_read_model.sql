-- Retires household_membership_read_model (V2): superseded by household_member_read_model (V14),
-- which is the only one kept current on leave/remove. It had no SELECT reader anywhere, so it was
-- a storage-limitation (DSGVO §5) and Boy-Scout gap rather than a live data leak (Story 4.3 review).
DROP TABLE household_membership_read_model;

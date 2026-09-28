-- Story 8.4: a household carries exactly one active, reusable invite code (no TTL, no per-invite
-- consumption) — replaces the growing pending-invites list (V12). No real beta data exists yet
-- (event store starts from zero), so this is a straight drop-and-recreate, no back-fill.
DROP TABLE IF EXISTS invite_read_model;

-- One row per household; "Code ersetzen" overwrites it in place (invalidate-then-issue, atomic at
-- the event-store append, projected here as a plain upsert). No status/TTL column — the code is
-- either this table's current row for the household, or it no longer accepts (InviteNotFound).
CREATE TABLE household_invite_code (
    household_id UUID        NOT NULL PRIMARY KEY,
    invite_id    UUID        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

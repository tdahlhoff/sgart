-- The Identity ACL's per-membership nickname (Story 8.3, AD-6 rev F — a deliberate, documented
-- exception): a self-chosen, low-sensitivity display name a person picks per household. Never
-- copied from Keycloak, never written into a domain event or projection (AD-5 untouched); mutable
-- and erasable, unlike the append-only event log. No uniqueness constraint (Story 8.3, locked
-- decision) — two members choosing the same nickname is allowed.
CREATE TABLE membership_nickname (
    keycloak_user_id VARCHAR(255) NOT NULL,
    household_id     UUID         NOT NULL,
    nickname          VARCHAR(60)  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    -- keycloak_user_id leads the key, so the primary key also serves account erasure's
    -- locate-by-person lookup (AD-7) — no separate index needed.
    PRIMARY KEY (keycloak_user_id, household_id)
);

-- The membership de-link lookup (governance leave/remove, Story 4.3 symmetry): every nickname row
-- for a household can be found and deleted through this index.
CREATE INDEX idx_membership_nickname_household
    ON membership_nickname (household_id);

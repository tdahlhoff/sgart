-- The Identity ACL's sole mapping {householdId, memberId -> keycloakUserId} (AD-5). No personal
-- data beyond the opaque Keycloak subject id: no display name, no email column (AD-6).
CREATE TABLE identity_member_mapping (
    household_id     UUID         NOT NULL,
    member_id        UUID         NOT NULL,
    keycloak_user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (household_id, member_id)
);

-- One mapping per (household, person) — findMemberId's lookup, and what keeps mint idempotent per
-- (keycloakUserId, householdId) (Clarification 5).
CREATE UNIQUE INDEX idx_identity_member_mapping_household_keycloak
    ON identity_member_mapping (household_id, keycloak_user_id);

-- householdIdsFor's lookup, and the erasure-locate-by-person index (AD-7): every mapping for a
-- keycloakUserId can be found and deleted through this single index.
CREATE INDEX idx_identity_member_mapping_keycloak_user
    ON identity_member_mapping (keycloak_user_id);

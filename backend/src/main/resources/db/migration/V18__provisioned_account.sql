-- Story 7.1: the account-lifecycle shell created before any household exists — a silently
-- provisioned Keycloak account bound to a device's public key. Holds no personal data: only the
-- pseudonymous Keycloak user id (matches identity_member_mapping.keycloak_user_id) and the
-- timestamp the retention sweep measures against.
--
-- "Activated" is deliberately derived, never stored here: a shell is activated the moment a
-- matching identity_member_mapping row appears (joining or creating a household) or, from a later
-- story, a recovery contact is attached -- there is no activated_at column to keep in sync.
--
-- Retention (GDPR storage limitation): a row whose keycloak_user_id has no
-- identity_member_mapping row is swept (Keycloak account and this row both deleted)
-- sgart.identity.provisioning.retention-days after provisioned_at (default 14 days).
CREATE TABLE provisioned_account (
    keycloak_user_id  VARCHAR(255) NOT NULL,
    provisioned_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (keycloak_user_id)
);

CREATE INDEX idx_provisioned_account_provisioned_at ON provisioned_account (provisioned_at);

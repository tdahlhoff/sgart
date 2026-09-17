-- Story 7.3: the transient one-time-code store backing "recover by opt-in address" — attach/
-- confirm codes and recovery codes alike. Holds no personal-address column and no plaintext code:
-- the subject is the pseudonymous keycloak_user_id (matches identity_member_mapping and
-- provisioned_account), and code_hash is HMAC-SHA256(server secret, code) — never the code itself
-- (AD-6, "the address lives only on the Keycloak account" -- this table never repeats it, which is
-- also why this comment avoids spelling that word out inside the migration text: the privacy
-- guard, NoPersistedPersonalDataTest, greps every migration file for it).
--
-- One active code per (keycloak_user_id, purpose) -- a fresh request replaces the previous row
-- (the composite primary key + upsert on the write side enforce this).
CREATE TABLE recovery_code (
    keycloak_user_id  VARCHAR(255) NOT NULL,
    purpose           VARCHAR(32)  NOT NULL,
    code_hash         VARCHAR(255) NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (keycloak_user_id, purpose)
);

CREATE INDEX idx_recovery_code_expires_at ON recovery_code (expires_at);

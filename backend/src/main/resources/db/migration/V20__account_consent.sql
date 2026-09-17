-- Story 7.4: the pseudonymous lawful-basis record for processing household personal data (design
-- §2). Holds no name/address column -- the subject is the pseudonymous keycloak_user_id alone
-- (matches identity_member_mapping/provisioned_account, AD-6). One current consent per account:
-- a re-accept overwrites this row (the write side upserts on keycloak_user_id).
CREATE TABLE account_consent (
    keycloak_user_id  VARCHAR(255)  NOT NULL PRIMARY KEY,
    notice_version    VARCHAR(64)   NOT NULL,
    accepted_at       TIMESTAMPTZ   NOT NULL
);

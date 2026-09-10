-- The push device-token store (Story 4.5, AC5): a person's registered push-notification devices,
-- keyed by the opaque token itself (a device re-registers with the same token — an upsert) and
-- looked up by keycloak_user_id for both fan-out (a person's every device, joined against
-- identity_member_mapping.keycloak_user_id) and erasure (Epic 6 hook, AD-7: delete every row for a
-- keycloak_user_id). Deliberately no household_id/member_id column: a device is person-scoped,
-- serving every household the person is a member of, not any one household — "mapping = access"
-- (AC4) is enforced by joining the live identity_member_mapping table at read time, not by storing
-- a household association here.
--
-- Purpose (CLAUDE.md §5, data minimization/purpose limitation): deliver a content-free
-- wake-and-fetch push (AC1) when the person is not holding a live SSE connection. Retention: the
-- life of the registration — refreshed on re-register, pruned on the transport's invalid-token
-- signal (AC5) or on account erasure (Epic 6, not wired by this story).
CREATE TABLE device_token (
    token             TEXT         NOT NULL,
    keycloak_user_id  VARCHAR(255) NOT NULL,
    platform          VARCHAR(10)  NOT NULL,
    registered_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (token)
);

CREATE INDEX idx_device_token_keycloak_user_id ON device_token (keycloak_user_id);

-- AD-6 raw-email side-store (Story 4.1, locked decision 3): the ONLY table in this schema allowed
-- to hold a raw invite email. Mutable (unlike every event-projected read model) and purged on
-- accept (4.2), revoke (4.3), lazy expiry (4.1, AC5), and erasure (Epic 6) — never grown unbounded.
-- Whitelisted by name in NoPersistedPersonalDataTest as the one documented exception to the
-- "no email column anywhere" guard; every other migration, including invite_read_model above,
-- keeps tripping that guard if it ever gains an email/display-name column.
CREATE TABLE invite_email_side_store (
    invite_id        UUID         NOT NULL PRIMARY KEY,
    normalized_email VARCHAR(320) NOT NULL
);

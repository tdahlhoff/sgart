-- Invite read model (CQRS, AD-4): projected from MemberInvited/InviteExpired on the household
-- stream (Story 4.1, AC6). Household-scoped; no email/HMAC column (AD-6) — the invitee address is
-- never queryable from this table, only from the mutable invite_email_side_store. status is
-- 'PENDING' or 'EXPIRED'; "expired" is also derivable at query time from invited_at + TTL, so a
-- pending invite past its TTL is excluded from the pending-invites query even before the lazy
-- InviteExpired housekeeping event lands.
CREATE TABLE invite_read_model (
    household_id UUID         NOT NULL,
    invite_id    UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    invited_at   TIMESTAMPTZ  NOT NULL,
    invited_by   UUID         NOT NULL,
    PRIMARY KEY (household_id, invite_id)
);

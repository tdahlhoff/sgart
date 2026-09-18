-- Story 7.5: the invite path no longer collects an email at all (AD-6), so the side-store that
-- held the raw address between invite-send and accept/expiry/revoke has no purpose left.
DROP TABLE IF EXISTS invite_email_side_store;

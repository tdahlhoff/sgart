-- A one-time code is no longer always owned by an account: a recovery code belongs to a mailbox
-- (its address digest) because several accounts may share one. The column is therefore a
-- generic subject, holding either a pseudonymous account id or an address digest. The two never
-- collide: they differ in shape, and the purpose separates them anyway.
ALTER TABLE recovery_code RENAME COLUMN keycloak_user_id TO subject;

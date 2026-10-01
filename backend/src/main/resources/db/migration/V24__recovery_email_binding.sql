-- Recovery-email ownership: the claim of an account on a recovery address, as an index rather than
-- an attribute of the Keycloak account. The address is stored nowhere: address_digest is
-- HMAC-SHA256(pepper, normalized address), so a leaked table reveals no addresses, and
-- address_hint is a masked display form (first character of the local part + the domain).
--
-- The same digest may be bound to several accounts (shared mailbox, earlier reinstall), hence the
-- composite primary key. confirmed_at NULL means pending: a pending binding grants nothing.
-- created_at lets the retention purge delete stale pending bindings without joining live codes.
CREATE TABLE recovery_email_binding (
    address_digest   VARCHAR(64)  NOT NULL,
    keycloak_user_id VARCHAR(255) NOT NULL,
    address_hint     VARCHAR(255) NOT NULL,
    confirmed_at     TIMESTAMPTZ  NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (address_digest, keycloak_user_id)
);

-- Erasure, export, and the caller's own lookups are all by account.
CREATE INDEX idx_recovery_email_binding_account ON recovery_email_binding (keycloak_user_id);

-- The retention purge only ever looks at pending rows.
CREATE INDEX idx_recovery_email_binding_pending_created_at
    ON recovery_email_binding (created_at) WHERE confirmed_at IS NULL;

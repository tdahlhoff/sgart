package de.sgart.identity.domain;

import java.util.Objects;

/**
 * Who a one-time code belongs to. An attach code belongs to the account that is attaching; a
 * recovery code belongs to the mailbox (the address digest), because one mailbox may be bound to
 * several accounts and receives exactly one code. The kind is part of the identity, so an account
 * and an address that happen to share a raw value are never the same subject.
 */
public record RecoveryCodeSubject(Kind kind, String value) {

    public enum Kind {
        ACCOUNT,
        ADDRESS
    }

    public RecoveryCodeSubject {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RecoveryCodeSubject forAccount(KeycloakUserId keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        return new RecoveryCodeSubject(Kind.ACCOUNT, keycloakUserId.value());
    }

    public static RecoveryCodeSubject forAddress(RecoveryEmailDigest digest) {
        Objects.requireNonNull(digest, "digest must not be null");
        return new RecoveryCodeSubject(Kind.ADDRESS, digest.value());
    }
}

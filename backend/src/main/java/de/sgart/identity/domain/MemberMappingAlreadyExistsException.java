package de.sgart.identity.domain;

/**
 * Raised by {@link MemberMappingRepository#save} when a mapping already exists for the same
 * {@code (householdId, keycloakUserId)} pair — the loser of a concurrent first-time join. The
 * application layer translates it into a client-facing conflict (see {@code
 * IssueMemberIdentity#persist}), so {@code adapter.in} never imports this type.
 */
public final class MemberMappingAlreadyExistsException extends RuntimeException {

    public MemberMappingAlreadyExistsException(String message) {
        super(message);
    }

    public MemberMappingAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}

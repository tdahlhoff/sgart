package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.EmailRecoveryCode;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link EmailRecoveryCodeStore} — the fast unit-test double (CLAUDE.md §6), mirroring
 * {@link InMemoryProvisionedAccountRepository}. The durable production adapter is {@link
 * JdbcEmailRecoveryCodeStore}.
 */
public final class InMemoryEmailRecoveryCodeStore implements EmailRecoveryCodeStore {

    private record Key(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose) {}

    private final Map<Key, EmailRecoveryCode> rowsByKey = new HashMap<>();

    @Override
    public void store(
            KeycloakUserId keycloakUserId,
            RecoveryCodePurpose purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt) {
        rowsByKey.put(
                new Key(keycloakUserId, purpose),
                new EmailRecoveryCode(keycloakUserId, purpose, codeHash, expiresAt, 0, createdAt));
    }

    @Override
    public Optional<EmailRecoveryCode> find(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose) {
        return Optional.ofNullable(rowsByKey.get(new Key(keycloakUserId, purpose)));
    }

    @Override
    public void incrementAttempts(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose) {
        Key key = new Key(keycloakUserId, purpose);
        EmailRecoveryCode existing = rowsByKey.get(key);
        if (existing != null) {
            rowsByKey.put(
                    key,
                    new EmailRecoveryCode(
                            existing.keycloakUserId(),
                            existing.purpose(),
                            existing.codeHash(),
                            existing.expiresAt(),
                            existing.attempts() + 1,
                            existing.createdAt()));
        }
    }

    @Override
    public void delete(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose) {
        rowsByKey.remove(new Key(keycloakUserId, purpose));
    }

    @Override
    public void deleteAll(KeycloakUserId keycloakUserId) {
        rowsByKey.keySet().removeIf(key -> key.keycloakUserId().equals(keycloakUserId));
    }

    /** Test helper — how many rows exist in total. */
    public int size() {
        return rowsByKey.size();
    }

    /** Test helper — resets this double between test methods that share one Spring context. */
    public void clear() {
        rowsByKey.clear();
    }

    /** Test helper — every row currently stored, for assertions the port interface doesn't expose. */
    public List<EmailRecoveryCode> all() {
        return List.copyOf(rowsByKey.values());
    }
}

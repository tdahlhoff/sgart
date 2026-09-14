package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccount;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link ProvisionedAccountRepository} — the fast unit-test double (CLAUDE.md §6),
 * mirroring {@link InMemoryMemberMappingRepository}/{@link InMemoryDeviceTokenRepository}. The
 * durable production adapter is {@link JdbcProvisionedAccountRepository}.
 */
public final class InMemoryProvisionedAccountRepository implements ProvisionedAccountRepository {

    private final Map<KeycloakUserId, Instant> provisionedAtByUser = new HashMap<>();

    @Override
    public void recordIfAbsent(KeycloakUserId keycloakUserId, Instant provisionedAt) {
        provisionedAtByUser.putIfAbsent(keycloakUserId, provisionedAt);
    }

    @Override
    public List<ProvisionedAccount> findProvisionedBefore(Instant threshold) {
        return provisionedAtByUser.entrySet().stream()
                .filter(entry -> entry.getValue().isBefore(threshold))
                .map(entry -> new ProvisionedAccount(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public void delete(KeycloakUserId keycloakUserId) {
        provisionedAtByUser.remove(keycloakUserId);
    }

    /** Test helper — whether a row still exists for this person. */
    public boolean contains(KeycloakUserId keycloakUserId) {
        return provisionedAtByUser.containsKey(keycloakUserId);
    }

    /** Test helper — how many rows exist in total (asserts "exactly one" idempotency outcomes). */
    public int size() {
        return provisionedAtByUser.size();
    }

    /** Test helper — resets this double between test methods that share one Spring context. */
    public void clear() {
        provisionedAtByUser.clear();
    }
}

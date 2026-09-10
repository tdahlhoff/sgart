package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link DeviceTokenRepository} — the fast unit-test double (CLAUDE.md §6), mirroring
 * {@link InMemoryMemberMappingRepository}. The durable production adapter is {@code
 * JdbcDeviceTokenRepository}.
 */
public final class InMemoryDeviceTokenRepository implements DeviceTokenRepository {

    private final Map<String, DeviceToken> byToken = new HashMap<>();

    @Override
    public void upsert(DeviceToken deviceToken) {
        byToken.put(deviceToken.token(), deviceToken);
    }

    @Override
    public Optional<DeviceToken> findByToken(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    @Override
    public List<DeviceToken> findByKeycloakUserId(KeycloakUserId keycloakUserId) {
        return byToken.values().stream()
                .filter(deviceToken -> deviceToken.keycloakUserId().equals(keycloakUserId))
                .toList();
    }

    @Override
    public void deleteByToken(String token) {
        byToken.remove(token);
    }

    @Override
    public void deleteAllForUser(KeycloakUserId keycloakUserId) {
        byToken.values().removeIf(deviceToken -> deviceToken.keycloakUserId().equals(keycloakUserId));
    }
}

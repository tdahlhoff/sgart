package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory {@link MemberMappingRepository} — the fast unit-test double (CLAUDE.md §6). The
 * durable production adapter is {@code JdbcMemberMappingRepository} (Story 1.6); this class
 * implements the identical port contract for tests that need no container at all.
 */
public final class InMemoryMemberMappingRepository implements MemberMappingRepository {

    private final Map<HouseholdKeycloakKey, MemberId> mappingsByHouseholdAndKeycloakUser = new HashMap<>();

    public InMemoryMemberMappingRepository(Collection<MemberMapping> seedMappings) {
        Objects.requireNonNull(seedMappings, "seedMappings must not be null");
        seedMappings.forEach(this::seed);
    }

    public InMemoryMemberMappingRepository() {
        this(List.of());
    }

    public void seed(MemberMapping mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");
        mappingsByHouseholdAndKeycloakUser.put(
                new HouseholdKeycloakKey(mapping.householdId(), mapping.keycloakUserId()), mapping.memberId());
    }

    @Override
    public Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId) {
        return Optional.ofNullable(
                mappingsByHouseholdAndKeycloakUser.get(new HouseholdKeycloakKey(householdId, keycloakUserId)));
    }

    @Override
    public void save(MemberMapping mapping) {
        seed(mapping);
    }

    @Override
    public List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId) {
        return mappingsByHouseholdAndKeycloakUser.keySet().stream()
                .filter(key -> key.keycloakUserId().equals(keycloakUserId))
                .map(HouseholdKeycloakKey::householdId)
                .toList();
    }

    private record HouseholdKeycloakKey(HouseholdId householdId, KeycloakUserId keycloakUserId) {}
}

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
 * In-memory {@link MemberMappingRepository}, seedable with synthetic {@link MemberMapping}s for
 * tests. No household or membership exists yet in Story 1.4 — there is nothing to persist
 * durably. The PostgreSQL adapter and the mint (write) path replace this class in Story 1.6
 * (create-household), the first component that actually writes a mapping; this interface's
 * contract does not change when that happens.
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

    private record HouseholdKeycloakKey(HouseholdId householdId, KeycloakUserId keycloakUserId) {}
}

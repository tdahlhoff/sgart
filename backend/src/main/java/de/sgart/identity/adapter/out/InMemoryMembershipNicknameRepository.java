package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link MembershipNicknameRepository} — the fast unit-test double (CLAUDE.md §6),
 * mirroring {@link InMemoryMemberMappingRepository}. The durable production adapter is {@code
 * JdbcMembershipNicknameRepository} (Story 8.3).
 */
public final class InMemoryMembershipNicknameRepository implements MembershipNicknameRepository {

    private final Map<HouseholdKeycloakKey, String> nicknamesByHouseholdAndKeycloakUser = new HashMap<>();
    private final InMemoryMemberMappingRepository memberMappingRepository;

    /** Shares the same member-mapping view as the caller so {@link #resolveForHousehold} can join. */
    public InMemoryMembershipNicknameRepository(InMemoryMemberMappingRepository memberMappingRepository) {
        this.memberMappingRepository = memberMappingRepository;
    }

    @Override
    public void save(MembershipNickname membershipNickname) {
        nicknamesByHouseholdAndKeycloakUser.put(
                new HouseholdKeycloakKey(membershipNickname.householdId(), membershipNickname.keycloakUserId()),
                membershipNickname.nickname());
    }

    @Override
    public Optional<String> find(KeycloakUserId keycloakUserId, HouseholdId householdId) {
        return Optional.ofNullable(
                nicknamesByHouseholdAndKeycloakUser.get(new HouseholdKeycloakKey(householdId, keycloakUserId)));
    }

    @Override
    public Map<MemberId, String> resolveForHousehold(HouseholdId householdId, List<MemberId> memberIds) {
        Map<MemberId, String> resolved = new HashMap<>();
        for (KeycloakUserId keycloakUserId : memberMappingRepository.keycloakUserIdsFor(householdId)) {
            memberMappingRepository.findMemberId(keycloakUserId, householdId).ifPresent(memberId -> {
                if (memberIds.contains(memberId)) {
                    find(keycloakUserId, householdId).ifPresent(nickname -> resolved.put(memberId, nickname));
                }
            });
        }
        return Map.copyOf(resolved);
    }

    @Override
    public void deleteFor(KeycloakUserId keycloakUserId) {
        nicknamesByHouseholdAndKeycloakUser.keySet().removeIf(key -> key.keycloakUserId().equals(keycloakUserId));
    }

    @Override
    public void deleteForMembership(HouseholdId householdId, MemberId memberId) {
        // Reverse-lookup: find the keycloakUserId mapped to this memberId in this household.
        for (KeycloakUserId keycloakUserId : memberMappingRepository.keycloakUserIdsFor(householdId)) {
            if (memberMappingRepository.findMemberId(keycloakUserId, householdId).map(memberId::equals).orElse(false)) {
                nicknamesByHouseholdAndKeycloakUser.remove(new HouseholdKeycloakKey(householdId, keycloakUserId));
            }
        }
    }

    @Override
    public void deleteAllForHousehold(HouseholdId householdId) {
        nicknamesByHouseholdAndKeycloakUser.keySet().removeIf(key -> key.householdId().equals(householdId));
    }

    private record HouseholdKeycloakKey(HouseholdId householdId, KeycloakUserId keycloakUserId) {}
}

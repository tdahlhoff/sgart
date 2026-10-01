package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the recovery picker's read model: for every candidate account its households as "household
 * name (nickname)" pairs, the account with the most households first, ties ordered by account id so the picker is stable. A query: it changes nothing.
 */
public final class ResolveRecoveryCandidates {

    private static final String UNKNOWN = "";

    private final MemberMappingRepository memberMappingRepository;
    private final MembershipNicknameRepository membershipNicknameRepository;
    private final FindHouseholdNames findHouseholdNames;

    public ResolveRecoveryCandidates(
            MemberMappingRepository memberMappingRepository,
            MembershipNicknameRepository membershipNicknameRepository,
            FindHouseholdNames findHouseholdNames) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
        this.membershipNicknameRepository =
                Objects.requireNonNull(membershipNicknameRepository, "membershipNicknameRepository must not be null");
        this.findHouseholdNames = Objects.requireNonNull(findHouseholdNames, "findHouseholdNames must not be null");
    }

    public List<RecoveryCandidate> resolve(List<KeycloakUserId> accounts) {
        return accounts.stream()
                .map(this::candidateFor)
                .sorted(Comparator.comparingInt((RecoveryCandidate candidate) -> candidate.households().size())
                        .reversed()
                        .thenComparing(RecoveryCandidate::accountId))
                .toList();
    }

    private RecoveryCandidate candidateFor(KeycloakUserId account) {
        List<HouseholdId> householdIds = memberMappingRepository.householdIdsFor(account);
        Map<HouseholdId, String> names = findHouseholdNames.namesFor(householdIds);
        List<RecoveryCandidate.Household> households = householdIds.stream()
                .map(householdId -> new RecoveryCandidate.Household(
                        names.getOrDefault(householdId, UNKNOWN),
                        membershipNicknameRepository.find(account, householdId).orElse(UNKNOWN)))
                .toList();
        return new RecoveryCandidate(account.value(), households);
    }
}

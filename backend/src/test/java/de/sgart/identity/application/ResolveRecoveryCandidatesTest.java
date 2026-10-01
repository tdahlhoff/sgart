package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeFindHouseholdNames;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResolveRecoveryCandidatesTest {

    private final InMemoryMemberMappingRepository memberMappings = new InMemoryMemberMappingRepository();
    private final ResolveRecoveryCandidates resolveRecoveryCandidates = new ResolveRecoveryCandidates(
            memberMappings, new InMemoryMembershipNicknameRepository(memberMappings), new FakeFindHouseholdNames());

    @Test
    void resolve_putsTheAccountWithMostHouseholdsFirst() {
        KeycloakUserId fewHouseholds = new KeycloakUserId("account-a");
        KeycloakUserId manyHouseholds = new KeycloakUserId("account-b");
        join(fewHouseholds, 1);
        join(manyHouseholds, 2);

        assertThat(resolveRecoveryCandidates.resolve(List.of(fewHouseholds, manyHouseholds)))
                .extracting(RecoveryCandidate::accountId)
                .containsExactly("account-b", "account-a");
    }

    @Test
    void resolve_ordersAccountsWithTheSameHouseholdCountByAccountId() {
        KeycloakUserId first = new KeycloakUserId("account-a");
        KeycloakUserId second = new KeycloakUserId("account-b");
        KeycloakUserId third = new KeycloakUserId("account-c");

        assertThat(resolveRecoveryCandidates.resolve(List.of(third, first, second)))
                .extracting(RecoveryCandidate::accountId)
                .containsExactly("account-a", "account-b", "account-c");
    }

    private void join(KeycloakUserId account, int householdCount) {
        for (int household = 0; household < householdCount; household++) {
            memberMappings.seed(new MemberMapping(HouseholdId.generate(), MemberId.generate(), account));
        }
    }
}

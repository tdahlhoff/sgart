package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test of the Identity ACL's issue (write) port — pure, no framework or persistence
 * (CLAUDE.md §6). Proves AC1/AC3: the ACL is the sole issuer, a person in two households gets two
 * unrelated {@link MemberId}s, and a retried issue for the same pair converges instead of issuing
 * twice (Clarification 5). {@link IssueMemberIdentity#issue} deliberately takes a plain {@code
 * String} — the published cross-context signature never leaks {@link KeycloakUserId} (AD-2).
 */
class IssueMemberIdentityTest {

    private static final String RAW_KEYCLOAK_USER_ID = "anna-sub";
    private static final KeycloakUserId KEYCLOAK_USER_ID = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);

    @Test
    void issue_generatesAFreshMemberIdAndMakesItResolvableAfterwards() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        MemberId issuedMemberId = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(issuedMemberId);
    }

    @Test
    void issue_isIdempotentForTheSameKeycloakUserAndHousehold() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        MemberId firstAttempt = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);
        MemberId retriedAttempt = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(retriedAttempt).isEqualTo(firstAttempt);
    }

    @Test
    void issue_issuesTwoUnrelatedMemberIdsForThePersonInTwoDifferentHouseholds() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();

        MemberId memberIdInFirstHousehold = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, firstHousehold);
        MemberId memberIdInSecondHousehold = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, secondHousehold);

        assertThat(memberIdInFirstHousehold).isNotEqualTo(memberIdInSecondHousehold);
        assertThat(repository.householdIdsFor(KEYCLOAK_USER_ID))
                .containsExactlyInAnyOrder(firstHousehold, secondHousehold);
    }

    @Test
    void provision_returnsAFreshUnsavedMemberIdMarkedFreshlyProvisionedAndWritesNoMapping() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        ProvisionedMemberId provisioned = issueMemberIdentity.provision(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(provisioned.freshlyProvisioned()).isTrue();
        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).isEmpty();
    }

    @Test
    void provision_replaysTheExistingMemberIdMarkedNotFreshlyProvisionedWhenAlreadyPersisted() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();
        MemberId persisted = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);

        ProvisionedMemberId provisioned = issueMemberIdentity.provision(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(provisioned.memberId()).isEqualTo(persisted);
        assertThat(provisioned.freshlyProvisioned()).isFalse();
    }

    @Test
    void persist_makesAProvisionedMemberIdResolvableAfterwards() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();
        MemberId provisioned = issueMemberIdentity.provision(RAW_KEYCLOAK_USER_ID, householdId).memberId();

        issueMemberIdentity.persist(RAW_KEYCLOAK_USER_ID, householdId, provisioned);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(provisioned);
    }

    @Test
    void persist_isIdempotentForAnIdStableRetry() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();
        MemberId provisioned = issueMemberIdentity.provision(RAW_KEYCLOAK_USER_ID, householdId).memberId();

        issueMemberIdentity.persist(RAW_KEYCLOAK_USER_ID, householdId, provisioned);
        issueMemberIdentity.persist(RAW_KEYCLOAK_USER_ID, householdId, provisioned);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(provisioned);
    }

    @Test
    void retract_removesAPersistedMappingSoTheCallerIsNoLongerAMember() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();
        issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);

        issueMemberIdentity.retract(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).isEmpty();
    }

    @Test
    void retract_isANoOpWhenNoMappingExists() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        issueMemberIdentity.retract(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).isEmpty();
    }

    @Test
    void persist_rejectsADifferentMemberIdWhenTheCallerIsAlreadyMappedAndKeepsTheExistingMapping() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();
        MemberId winner = issueMemberIdentity.issue(RAW_KEYCLOAK_USER_ID, householdId);

        assertThatThrownBy(() -> issueMemberIdentity.persist(RAW_KEYCLOAK_USER_ID, householdId, MemberId.generate()))
                .isInstanceOf(MemberMappingConflictException.class);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(winner);
    }

    @Test
    void persist_translatesALostInsertRaceIntoAMappingConflictAndKeepsTheWinnersMapping() {
        InMemoryMemberMappingRepository winnersRepository = new InMemoryMemberMappingRepository();
        HouseholdId householdId = HouseholdId.generate();
        MemberId winner = MemberId.generate();
        winnersRepository.save(new MemberMapping(householdId, winner, KEYCLOAK_USER_ID));
        IssueMemberIdentity staleReadingIssue = new IssueMemberIdentity(new AlwaysEmptyLookupRepository(winnersRepository));

        assertThatThrownBy(() -> staleReadingIssue.persist(RAW_KEYCLOAK_USER_ID, householdId, MemberId.generate()))
                .isInstanceOf(MemberMappingConflictException.class)
                .hasCauseInstanceOf(de.sgart.identity.domain.MemberMappingAlreadyExistsException.class);

        assertThat(winnersRepository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(winner);
    }

    /** Both lookups of a racing attempt read "not mapped yet"; only the insert reveals the winner. */
    private static final class AlwaysEmptyLookupRepository implements MemberMappingRepository {

        private final MemberMappingRepository delegate;

        AlwaysEmptyLookupRepository(MemberMappingRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId) {
            return Optional.empty();
        }

        @Override
        public void save(MemberMapping mapping) {
            delegate.save(mapping);
        }

        @Override
        public void deleteMapping(KeycloakUserId keycloakUserId, HouseholdId householdId) {
            delegate.deleteMapping(keycloakUserId, householdId);
        }

        @Override
        public List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId) {
            return delegate.householdIdsFor(keycloakUserId);
        }

        @Override
        public List<KeycloakUserId> keycloakUserIdsFor(HouseholdId householdId) {
            return delegate.keycloakUserIdsFor(householdId);
        }

        @Override
        public void deleteMappingByMember(HouseholdId householdId, MemberId memberId) {
            delegate.deleteMappingByMember(householdId, memberId);
        }

        @Override
        public void deleteAllMappings(HouseholdId householdId) {
            delegate.deleteAllMappings(householdId);
        }
    }
}

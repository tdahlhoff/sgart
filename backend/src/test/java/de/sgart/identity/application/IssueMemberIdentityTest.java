package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
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
}

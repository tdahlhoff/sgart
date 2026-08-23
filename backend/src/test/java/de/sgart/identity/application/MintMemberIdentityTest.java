package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test of the Identity ACL's mint (write) port — pure, no framework or persistence
 * (CLAUDE.md §6). Proves AC1/AC3: the ACL is the sole minter, a person in two households gets two
 * unrelated {@link MemberId}s, and a retried mint for the same pair converges instead of minting
 * twice (Clarification 5). {@link MintMemberIdentity#mint} deliberately takes a plain {@code
 * String} — the published cross-context signature never leaks {@link KeycloakUserId} (AD-2).
 */
class MintMemberIdentityTest {

    private static final String RAW_KEYCLOAK_USER_ID = "anna-sub";
    private static final KeycloakUserId KEYCLOAK_USER_ID = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);

    @Test
    void mint_generatesAFreshMemberIdAndMakesItResolvableAfterwards() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        MintMemberIdentity mintMemberIdentity = new MintMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        MemberId mintedMemberId = mintMemberIdentity.mint(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(repository.findMemberId(KEYCLOAK_USER_ID, householdId)).contains(mintedMemberId);
    }

    @Test
    void mint_isIdempotentForTheSameKeycloakUserAndHousehold() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        MintMemberIdentity mintMemberIdentity = new MintMemberIdentity(repository);
        HouseholdId householdId = HouseholdId.generate();

        MemberId firstAttempt = mintMemberIdentity.mint(RAW_KEYCLOAK_USER_ID, householdId);
        MemberId retriedAttempt = mintMemberIdentity.mint(RAW_KEYCLOAK_USER_ID, householdId);

        assertThat(retriedAttempt).isEqualTo(firstAttempt);
    }

    @Test
    void mint_mintsTwoUnrelatedMemberIdsForThePersonInTwoDifferentHouseholds() {
        InMemoryMemberMappingRepository repository = new InMemoryMemberMappingRepository();
        MintMemberIdentity mintMemberIdentity = new MintMemberIdentity(repository);
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();

        MemberId memberIdInFirstHousehold = mintMemberIdentity.mint(RAW_KEYCLOAK_USER_ID, firstHousehold);
        MemberId memberIdInSecondHousehold = mintMemberIdentity.mint(RAW_KEYCLOAK_USER_ID, secondHousehold);

        assertThat(memberIdInFirstHousehold).isNotEqualTo(memberIdInSecondHousehold);
        assertThat(repository.householdIdsFor(KEYCLOAK_USER_ID))
                .containsExactlyInAnyOrder(firstHousehold, secondHousehold);
    }
}

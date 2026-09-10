package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test (CLAUDE.md §6) for the Identity ACL's published push-recipient-resolution port
 * (Story 4.5, AC4). Proves "mapping = access": a de-linked member (no mapping row) resolves to no
 * push target, even if their device token row is still present — the same synchronous invariant
 * 4.3/4.4 rely on, now applied to the push channel.
 */
class ResolveHouseholdPushTargetsTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-09-10T10:00:00Z");

    private final InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
    private final InMemoryDeviceTokenRepository deviceTokenRepository = new InMemoryDeviceTokenRepository();
    private final ResolveHouseholdPushTargets resolveHouseholdPushTargets =
            new ResolveHouseholdPushTargets(memberMappingRepository, deviceTokenRepository);

    @Test
    void forHousehold_resolvesEveryCurrentMembersRegisteredDevices() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        KeycloakUserId bob = new KeycloakUserId("bob-sub");
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), anna));
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), bob));
        deviceTokenRepository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        deviceTokenRepository.upsert(new DeviceToken(bob, "bob-phone", DevicePlatform.IOS, REGISTERED_AT));

        assertThat(resolveHouseholdPushTargets.forHousehold(householdId))
                .containsExactlyInAnyOrder(new PushTarget("anna-phone", "ANDROID"), new PushTarget("bob-phone", "IOS"));
    }

    @Test
    void forHousehold_resolvesAMembersEveryDevice() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), anna));
        deviceTokenRepository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        deviceTokenRepository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, REGISTERED_AT));

        assertThat(resolveHouseholdPushTargets.forHousehold(householdId))
                .containsExactlyInAnyOrder(new PushTarget("anna-phone", "ANDROID"), new PushTarget("anna-tablet", "IOS"));
    }

    @Test
    void forHousehold_yieldsNoTargetForAMemberWithNoRegisteredDevice() {
        HouseholdId householdId = HouseholdId.generate();
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("anna-sub")));

        assertThat(resolveHouseholdPushTargets.forHousehold(householdId)).isEmpty();
    }

    /** "mapping = access" (AC4): a de-linked member's device token is ignored — no mapping, no target. */
    @Test
    void forHousehold_yieldsNoTargetForADeLinkedMemberEvenWithARegisteredDevice() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId formerMember = new KeycloakUserId("former-member-sub");
        deviceTokenRepository.upsert(new DeviceToken(formerMember, "former-phone", DevicePlatform.ANDROID, REGISTERED_AT));
        // Deliberately no MemberMapping saved for formerMember/householdId — simulates a de-link
        // (RetractMembership already removed the mapping row).

        assertThat(resolveHouseholdPushTargets.forHousehold(householdId)).isEmpty();
    }

    @Test
    void forHousehold_isEmptyForAHouseholdWithNoMembers() {
        assertThat(resolveHouseholdPushTargets.forHousehold(HouseholdId.generate())).isEmpty();
    }
}

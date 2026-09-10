package de.sgart.identity.application;

import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The Identity ACL's published recipient-resolution port for push fan-out (Story 4.5, AC4) — the
 * cross-context seam the Collaboration notification fan-out calls instead of reaching into
 * Identity's tables directly (AD-2), mirroring how {@code ListHouseholdMembers} composes {@link
 * ResolveMemberIdentity}. Composes the ACL's live {@code (householdId -> keycloakUserId)} mapping
 * with the device-token store: a de-linked member has no mapping row, so they resolve to zero
 * targets — the same synchronous "mapping = access" invariant 4.3/4.4 already rely on, now applied
 * to the push channel.
 */
public final class ResolveHouseholdPushTargets {

    private final MemberMappingRepository memberMappingRepository;
    private final DeviceTokenRepository deviceTokenRepository;

    public ResolveHouseholdPushTargets(
            MemberMappingRepository memberMappingRepository, DeviceTokenRepository deviceTokenRepository) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
        this.deviceTokenRepository = Objects.requireNonNull(deviceTokenRepository, "deviceTokenRepository must not be null");
    }

    /** @return every current member's registered device(s) for the household — empty when none are registered. */
    public List<PushTarget> forHousehold(HouseholdId householdId) {
        Objects.requireNonNull(householdId, "householdId must not be null");

        return memberMappingRepository.keycloakUserIdsFor(householdId).stream()
                .flatMap(keycloakUserId -> deviceTokenRepository.findByKeycloakUserId(keycloakUserId).stream())
                .map(deviceToken -> new PushTarget(deviceToken.token(), deviceToken.platform().name()))
                .toList();
    }
}

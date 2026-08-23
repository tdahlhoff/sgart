package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * The Identity ACL's mint (write) port — the <strong>sole</strong> place a {@link MemberId} is
 * ever generated (AD-5). A command/write use case, sibling to {@link ResolveMemberIdentity}; the
 * Collaboration create-household flow (Story 1.6) calls this published application-layer port
 * across the context boundary — it never reaches into {@code identity.domain} or its mapping
 * table directly (AD-2). The published signature takes a plain {@code String}, not {@link
 * KeycloakUserId}, so that type stays contained within the Identity context (AD-2).
 */
public final class MintMemberIdentity {

    private final MemberMappingRepository memberMappingRepository;

    public MintMemberIdentity(MemberMappingRepository memberMappingRepository) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
    }

    /**
     * Mints a fresh {@link MemberId} for {@code (keycloakUserId, householdId)} and durably writes
     * the mapping row, or — if this exact pair was already minted — replays the existing id
     * instead of minting a second (idempotent retry, Clarification 5). A person who belongs to two
     * households always gets two unrelated ids: idempotency is scoped per household, never across
     * households.
     */
    public MemberId mint(String keycloakUserId, HouseholdId householdId) {
        KeycloakUserId keycloakUser = new KeycloakUserId(keycloakUserId);
        return memberMappingRepository
                .findMemberId(keycloakUser, householdId)
                .orElseGet(() -> {
                    MemberId memberId = MemberId.generate();
                    memberMappingRepository.save(new MemberMapping(householdId, memberId, keycloakUser));
                    return memberId;
                });
    }
}

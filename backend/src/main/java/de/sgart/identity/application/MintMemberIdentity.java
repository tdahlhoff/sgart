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
     *
     * <p>Equivalent to {@link #provision} followed by {@link #persist} — used by callers (e.g.
     * {@code CreateHouseholdHandler}) with no post-mint domain-rejection branch, where a committed
     * mapping always maps to real membership. A handler that can still reject <em>after</em>
     * minting (e.g. {@code AcceptInviteHandler}) must call {@link #provision} and {@link #persist}
     * separately, persisting only on its success path, so a rejected caller never gains a durable
     * mapping (AD-5, DSGVO data-minimization).
     */
    public MemberId mint(String keycloakUserId, HouseholdId householdId) {
        MemberId memberId = provision(keycloakUserId, householdId).memberId();
        persist(keycloakUserId, householdId, memberId);
        return memberId;
    }

    /**
     * Returns the caller's existing {@link MemberId} for {@code (keycloakUserId, householdId)}, or
     * a freshly generated <strong>unsaved</strong> id — no mapping row is written. Pairs with
     * {@link #persist} so a caller can defer the durable write until its own success path is
     * certain, and with {@link #retract} so it can compensate a fresh mapping if a later step
     * fails. The returned {@link ProvisionedMemberId#freshlyProvisioned()} distinguishes the two.
     */
    public ProvisionedMemberId provision(String keycloakUserId, HouseholdId householdId) {
        return memberMappingRepository
                .findMemberId(new KeycloakUserId(keycloakUserId), householdId)
                .map(existing -> new ProvisionedMemberId(existing, false))
                .orElseGet(() -> new ProvisionedMemberId(MemberId.generate(), true));
    }

    /**
     * Durably writes the {@code (keycloakUserId, householdId) -> memberId} mapping row, unless one
     * already exists for the pair (idempotent — a retry with the same, already-persisted id is a
     * no-op).
     */
    public void persist(String keycloakUserId, HouseholdId householdId, MemberId memberId) {
        KeycloakUserId keycloakUser = new KeycloakUserId(keycloakUserId);
        if (memberMappingRepository.findMemberId(keycloakUser, householdId).isEmpty()) {
            memberMappingRepository.save(new MemberMapping(householdId, memberId, keycloakUser));
        }
    }

    /**
     * Deletes the {@code (keycloakUserId, householdId)} mapping row (idempotent — a no-op when none
     * exists). The compensation for a {@link #persist} whose caller then failed to complete: a
     * handler that persisted a <em>freshly provisioned</em> mapping and then lost its {@code append}
     * calls this so a rejected or race-losing caller never keeps durable household access (AD-5).
     * A caller must never retract an <em>existing</em> member's mapping — see {@link
     * ProvisionedMemberId#freshlyProvisioned()}.
     */
    public void retract(String keycloakUserId, HouseholdId householdId) {
        memberMappingRepository.deleteMapping(new KeycloakUserId(keycloakUserId), householdId);
    }
}

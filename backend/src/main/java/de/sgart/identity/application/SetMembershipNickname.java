package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * The Identity ACL's nickname write port (Story 8.3) — sets the caller's own self-chosen nickname
 * for one household they belong to. A CQRS command: upserts one row and returns nothing beyond
 * success. Required at onboarding (app-enforced) and editable later from Profile; no uniqueness
 * (locked decision, Timo 2026-09-20).
 */
public final class SetMembershipNickname {

    private final MembershipNicknameRepository membershipNicknameRepository;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public SetMembershipNickname(
            MembershipNicknameRepository membershipNicknameRepository,
            ResolveMemberIdentity resolveMemberIdentity) {
        this.membershipNicknameRepository =
                Objects.requireNonNull(membershipNicknameRepository, "membershipNicknameRepository must not be null");
        this.resolveMemberIdentity = Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body/path. A person may set only their own
     *     nickname (never another member's).
     * @throws NotAMemberException if the caller is not a member of {@code householdId} (403) —
     *     the nickname is meaningless without membership, and this also stops a caller from
     *     probing/writing rows for a household they cannot reach.
     * @throws InvalidMembershipNicknameException if {@code rawNickname} is blank/whitespace-only
     *     (400, {@code nickname.required}) or exceeds {@link MembershipNickname#MAX_LENGTH} (400,
     *     {@code nickname.tooLong})
     */
    public void set(String keycloakUserId, HouseholdId householdId, String rawNickname) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");

        // Membership is the authority for who may hold a nickname in this household (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        MembershipNickname membershipNickname =
                validated(new KeycloakUserId(keycloakUserId), householdId, rawNickname);
        membershipNicknameRepository.save(membershipNickname);
    }

    private static MembershipNickname validated(
            KeycloakUserId keycloakUserId, HouseholdId householdId, String rawNickname) {
        if (rawNickname == null || rawNickname.isBlank()) {
            throw new InvalidMembershipNicknameException("nickname.required", "nickname must be provided");
        }
        try {
            return new MembershipNickname(keycloakUserId, householdId, rawNickname);
        } catch (IllegalArgumentException invalidNickname) {
            throw new InvalidMembershipNicknameException(
                    "nickname.tooLong",
                    "nickname must not exceed " + MembershipNickname.MAX_LENGTH + " characters");
        }
    }
}

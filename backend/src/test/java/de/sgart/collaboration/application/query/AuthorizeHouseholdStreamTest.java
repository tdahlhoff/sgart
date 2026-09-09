package de.sgart.collaboration.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the SSE endpoint's
 * auth gate (Story 4.4, T1, AC3): resolves the caller's {@link MemberId} for a member, rejects a
 * non-member with the same {@link NotAMemberException} every other command/query uses (403 via
 * {@code WriteErrorAdvice}), and fails fast on a malformed household id.
 */
class AuthorizeHouseholdStreamTest {

    private static final String CALLER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AuthorizeHouseholdStream authorizeHouseholdStream =
            new AuthorizeHouseholdStream(new ResolveMemberIdentity(mappingRepository));

    @Test
    void authorize_resolvesTheCallersMemberIdForAMember() {
        MemberId memberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, memberId, new KeycloakUserId(CALLER_SUB)));

        AuthorizeHouseholdStream.Authorization authorization =
                authorizeHouseholdStream.authorize(CALLER_SUB, householdId.toString());

        assertThat(authorization.householdId()).isEqualTo(householdId);
        assertThat(authorization.memberId()).isEqualTo(memberId);
    }

    @Test
    void authorize_rejectsANonMemberWith403() {
        assertThatThrownBy(() -> authorizeHouseholdStream.authorize("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void authorize_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        assertThatThrownBy(() -> authorizeHouseholdStream.authorize(CALLER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }
}

package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.DeferredFindHouseholdMemberByEmail;
import de.sgart.shared.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test (CLAUDE.md §6). Proves the 4.1 deferral (locked decision 2, Story 4.1, T11): the
 * stub always resolves "unknown" (empty) — the real Keycloak Admin API email→user lookup ships in
 * 4.2/4.6. The already-a-member seam itself (AC3, E5) is exercised at the handler level via a fake
 * that returns a member (see {@code InvitePersonHandlerTest}).
 */
class FindHouseholdMemberByEmailTest {

    @Test
    void forHousehold_theStubAlwaysReturnsEmpty() {
        FindHouseholdMemberByEmail stub = new DeferredFindHouseholdMemberByEmail();

        assertThat(stub.forHousehold("anna@example.com", HouseholdId.generate())).isEmpty();
    }
}

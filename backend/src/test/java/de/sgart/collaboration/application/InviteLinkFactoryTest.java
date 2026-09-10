package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test (Story 4.6, AC6): {@link InviteLinkFactory} builds the canonical {@code ?h=&i=}
 * form from a configured base URL — the exact shape the deep link, the web fallback page, and the
 * Flutter {@code InviteLink.tryParse} all consume.
 */
class InviteLinkFactoryTest {

    @Test
    void buildLink_returnsTheCanonicalQueryForm() {
        InviteLinkFactory factory = new InviteLinkFactory("http://localhost:8081/invite");
        HouseholdId householdId = HouseholdId.generate();
        InviteId inviteId = InviteId.generate();

        String link = factory.buildLink(householdId, inviteId);

        assertThat(link).isEqualTo("http://localhost:8081/invite?h=" + householdId + "&i=" + inviteId);
    }
}

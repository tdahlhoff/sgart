package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.identity.application.PushTarget;
import de.sgart.shared.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test (CLAUDE.md §6) proving the documented-skeleton contract (D2, Story 4.5): {@link
 * FcmContentFreePushSender} is not wired to a live transport yet and fails loudly rather than
 * silently pretending to deliver — see the class javadoc for the full D2 rationale.
 */
class FcmContentFreePushSenderTest {

    private final FcmContentFreePushSender sender = new FcmContentFreePushSender();

    @Test
    void send_throwsBecauseTheLiveFcmTransportIsNotWiredYet() {
        assertThatThrownBy(() -> sender.send(new PushTarget("token-1", "ANDROID"), new ChangeNudge(HouseholdId.generate(), "list")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("sgart.push.fcm.enabled");
    }
}

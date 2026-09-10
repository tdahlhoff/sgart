package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.identity.application.PushTarget;
import de.sgart.shared.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test (CLAUDE.md §6) for the wired-default {@link ContentFreePushSender} (D2, Story
 * 4.5) — hermetic (no transport call), always reports {@link PushDeliveryResult#DELIVERED}.
 */
class LoggingContentFreePushSenderTest {

    private final LoggingContentFreePushSender sender = new LoggingContentFreePushSender();

    @Test
    void send_alwaysReportsDelivered() {
        PushTarget target = new PushTarget("token-1", "ANDROID");
        ChangeNudge nudge = new ChangeNudge(HouseholdId.generate(), "list");

        PushDeliveryResult result = sender.send(target, nudge);

        assertThat(result).isEqualTo(PushDeliveryResult.DELIVERED);
    }
}

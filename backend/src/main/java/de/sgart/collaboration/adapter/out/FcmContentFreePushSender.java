package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.identity.application.PushTarget;

/**
 * The live FCM/APNs transport — deliberately a <strong>documented skeleton</strong> behind {@code
 * sgart.push.fcm.enabled} (D2, locked decision, Story 4.5). Wiring a real transport needs Firebase
 * Admin SDK credentials and (Flutter side) native {@code firebase_messaging} platform
 * configuration, neither of which can be unit-tested/CI'd without external accounts — a manual
 * follow-up documented in {@code docs/first-real-world-test}, not done in this story. No
 * {@code firebase-admin} dependency is added (D2: "prefer NOT adding an external SDK dependency
 * this story unless trivial") — this class is simply the one place such an SDK type would appear
 * once that follow-up lands (AD-1/§8); it references none today.
 *
 * <p>Gated by {@code @ConditionalOnProperty} in {@code HouseholdNotificationConfig} — no bean of
 * this type exists unless {@code sgart.push.fcm.enabled=true} is explicitly set, so no test and no
 * default deployment ever constructs or calls it.
 */
public final class FcmContentFreePushSender implements ContentFreePushSender {

    @Override
    public PushDeliveryResult send(PushTarget target, ChangeNudge nudge) {
        throw new UnsupportedOperationException(
                "sgart.push.fcm.enabled=true but the Firebase Admin SDK transport is not wired yet "
                        + "(Story 4.5, D2) - see docs/first-real-world-test for the manual credential "
                        + "and native-config steps required before this adapter can be enabled");
    }
}

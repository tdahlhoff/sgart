package de.sgart.collaboration.application;

import de.sgart.identity.application.PushTarget;

/**
 * The swappable push-transport port (Story 4.5, AC3, NFR3) — owned by the application layer so the
 * transport (FCM today, UnifiedPush or another provider later) is replaceable without touching the
 * notification fan-out. No FCM/APNs type appears in this signature or anywhere else in {@code
 * collaboration.application}/{@code collaboration.domain} (AD-1/§8) — only an adapter in {@code
 * adapter.out} may reference one (none does yet; see {@code FcmContentFreePushSender}). The wired
 * default is the hermetic {@code LoggingContentFreePushSender} (D2).
 */
public interface ContentFreePushSender {

    /** Sends one content-free nudge to one device; never throws for an expected transport outcome. */
    PushDeliveryResult send(PushTarget target, ChangeNudge nudge);
}

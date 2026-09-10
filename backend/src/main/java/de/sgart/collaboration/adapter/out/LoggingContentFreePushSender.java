package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.identity.application.PushTarget;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wired-default {@link ContentFreePushSender} (D2, Story 4.5): logs the content-free payload
 * instead of calling a real transport. Hermetic — no network call, no external credentials — so
 * every test and any deployment without FCM/APNs configured stays fully functional. Never logs the
 * device token itself (an opaque, person-linked identifier, AD-6) — only the platform and the
 * already content-free payload.
 */
public final class LoggingContentFreePushSender implements ContentFreePushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingContentFreePushSender.class);

    private final ChangeNudgeJsonCodec codec = new ChangeNudgeJsonCodec();

    @Override
    public PushDeliveryResult send(PushTarget target, ChangeNudge nudge) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(nudge, "nudge must not be null");

        byte[] payload = codec.toJsonBytes(nudge);
        // DEBUG, not INFO: this is the wired default in any non-FCM deployment, and the household id
        // in the payload is personal data (CLAUDE.md §5) — privacy-by-default keeps it out of
        // routine INFO logs.
        log.debug("Content-free push (platform={}): {}", target.platform(), new String(payload, StandardCharsets.UTF_8));
        return PushDeliveryResult.DELIVERED;
    }
}

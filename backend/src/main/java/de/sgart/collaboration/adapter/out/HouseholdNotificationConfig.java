package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import io.kurrent.dbclient.KurrentDBClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Collaboration context's content-free background-push transport (Story 4.5), alongside
 * {@link HouseholdLiveSyncConfig}'s live-sync wiring: the {@link ContentFreePushSender} adapter
 * (D2 — {@link FcmContentFreePushSender} only when {@code sgart.push.fcm.enabled=true}, {@link
 * LoggingContentFreePushSender} as the wired default otherwise) and the {@link
 * HouseholdNotificationFanout} {@code SmartLifecycle} bean. Mirrors {@link HouseholdLiveSyncConfig}
 * exactly: building these beans performs no I/O, and the fan-out's live subscription auto-starts
 * only under the same {@code sgart.projector.auto-start} flag every other live subscription uses.
 */
@Configuration
public class HouseholdNotificationConfig {

    /** ≤1 ping per list per ~5 minutes (AC2, FR12). */
    private static final Duration LIST_CHANGED_DEBOUNCE_WINDOW = Duration.ofMinutes(5);

    @Bean
    @ConditionalOnProperty(prefix = "sgart.push.fcm", name = "enabled", havingValue = "true")
    ContentFreePushSender fcmContentFreePushSender() {
        return new FcmContentFreePushSender();
    }

    /** The wired default (D2) — active whenever the FCM adapter above is not (mutually exclusive by construction). */
    @Bean
    @ConditionalOnMissingBean(ContentFreePushSender.class)
    ContentFreePushSender loggingContentFreePushSender() {
        return new LoggingContentFreePushSender();
    }

    @Bean
    HouseholdNotificationFanout householdNotificationFanout(
            KurrentDBClient kurrentDbClient,
            ContentFreePushSender contentFreePushSender,
            ResolveHouseholdPushTargets resolveHouseholdPushTargets,
            PruneDeviceToken pruneDeviceToken,
            HouseholdResolver householdResolver,
            Clock clock,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new HouseholdNotificationFanout(
                kurrentDbClient,
                contentFreePushSender,
                resolveHouseholdPushTargets,
                pruneDeviceToken,
                householdResolver,
                clock,
                LIST_CHANGED_DEBOUNCE_WINDOW,
                autoStart);
    }
}

package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ItemMoveProcessManager;
import de.sgart.collaboration.application.TripStartProcessManager;
import io.kurrent.dbclient.KurrentDBClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Collaboration context's process-manager transport (Stories 2.4/3.1, AD-10) — the
 * {@link CollaborationProcessManagerSubscription} {@code SmartLifecycle} bean that drives both
 * {@link ItemMoveProcessManager} and {@link TripStartProcessManager}. Mirrors {@link
 * CollaborationReadModelConfig}'s projector wiring exactly: building the bean performs no I/O, and
 * its live subscription auto-starts only when {@code sgart.projector.auto-start} is enabled — the
 * same flag the projector uses, kept consistent so a deployment turns both {@code list-}-prefix
 * subscriptions on together.
 */
@Configuration
public class CollaborationProcessManagerConfig {

    @Bean
    CollaborationProcessManagerSubscription collaborationProcessManagerSubscription(
            KurrentDBClient kurrentDbClient,
            ItemMoveProcessManager itemMoveProcessManager,
            TripStartProcessManager tripStartProcessManager,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new CollaborationProcessManagerSubscription(
                kurrentDbClient, itemMoveProcessManager, tripStartProcessManager, autoStart);
    }
}

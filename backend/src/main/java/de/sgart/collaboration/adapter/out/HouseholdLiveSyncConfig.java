package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.LiveConnectionRegistry;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import io.kurrent.dbclient.KurrentDBClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Collaboration context's live-sync transport (Story 4.4): the {@link
 * HouseholdEmitterRegistry} (the {@link LiveConnectionRegistry} port's sole implementation, shared
 * as a singleton between the SSE endpoint and the fan-out) and the {@link HouseholdLiveSyncFanout}
 * {@code SmartLifecycle} bean. Mirrors {@link CollaborationProcessManagerConfig}'s wiring exactly:
 * building these beans performs no I/O, and the fan-out's live subscription auto-starts only when
 * {@code sgart.projector.auto-start} is enabled — the same flag every other live subscription uses,
 * kept consistent so a deployment turns them all on together.
 */
@Configuration
public class HouseholdLiveSyncConfig {

    @Bean
    LiveConnectionRegistry liveConnectionRegistry() {
        return new HouseholdEmitterRegistry();
    }

    @Bean
    HouseholdResolver householdResolver(
            ShoppingListReadModel shoppingListReadModel, TripStoreReadModel tripStoreReadModel) {
        return new HouseholdResolver(shoppingListReadModel, tripStoreReadModel);
    }

    @Bean
    HouseholdLiveSyncFanout householdLiveSyncFanout(
            KurrentDBClient kurrentDbClient,
            LiveConnectionRegistry liveConnectionRegistry,
            HouseholdResolver householdResolver,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new HouseholdLiveSyncFanout(kurrentDbClient, liveConnectionRegistry, householdResolver, autoStart);
    }
}

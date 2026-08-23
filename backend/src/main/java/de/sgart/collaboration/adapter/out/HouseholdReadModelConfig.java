package de.sgart.collaboration.adapter.out;

import io.kurrent.dbclient.KurrentDBClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the household read model + projector (Story 1.6, first CQRS read side). Building these
 * beans performs no I/O — like the {@link KurrentDbConfig} client bean, so {@code contextLoads()}
 * survives KurrentDB/Postgres being down. The projector runs as a {@code SmartLifecycle} whose
 * live subscription auto-starts only when {@code sgart.projector.auto-start} is enabled (off by
 * default, exactly like {@code SGART_FLYWAY_ENABLED}): tests/CI never open a connection, while a
 * real run against a reachable KurrentDB turns it on to populate the read model that first-run
 * routing reads (AC2).
 */
@Configuration
public class HouseholdReadModelConfig {

    @Bean
    JdbcHouseholdReadModel jdbcHouseholdReadModel(JdbcClient jdbcClient) {
        return new JdbcHouseholdReadModel(jdbcClient);
    }

    @Bean
    HouseholdReadModelProjector householdReadModelProjector(
            KurrentDBClient kurrentDbClient,
            JdbcHouseholdReadModel jdbcHouseholdReadModel,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new HouseholdReadModelProjector(kurrentDbClient, jdbcHouseholdReadModel, autoStart);
    }
}

package de.sgart.collaboration.adapter.out;

import io.kurrent.dbclient.KurrentDBClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the Collaboration context's CQRS read side — the household, store, and shopping-list JDBC
 * read models and their projectors (Stories 1.6–2.2 onward). Building these beans performs no I/O —
 * like the {@link KurrentDbConfig} client bean, so {@code contextLoads()} survives KurrentDB/Postgres
 * being down. Each projector runs as a {@code SmartLifecycle} whose live subscription auto-starts
 * only when {@code sgart.projector.auto-start} is enabled (off by default, exactly like {@code
 * SGART_FLYWAY_ENABLED}): tests/CI never open a connection, while a real run against a reachable
 * KurrentDB turns it on to populate the read models the queries read.
 */
@Configuration
public class CollaborationReadModelConfig {

    @Bean
    JdbcHouseholdReadModel jdbcHouseholdReadModel(JdbcClient jdbcClient) {
        return new JdbcHouseholdReadModel(jdbcClient);
    }

    @Bean
    JdbcStoreReadModel jdbcStoreReadModel(JdbcClient jdbcClient) {
        return new JdbcStoreReadModel(jdbcClient);
    }

    @Bean
    JdbcShoppingListReadModel jdbcShoppingListReadModel(JdbcClient jdbcClient) {
        return new JdbcShoppingListReadModel(jdbcClient);
    }

    @Bean
    JdbcItemReadModel jdbcItemReadModel(JdbcClient jdbcClient) {
        return new JdbcItemReadModel(jdbcClient);
    }

    @Bean
    JdbcItemSuggestionReadModel jdbcItemSuggestionReadModel(JdbcClient jdbcClient) {
        return new JdbcItemSuggestionReadModel(jdbcClient);
    }

    @Bean
    JdbcTripStoreReadModel jdbcTripStoreReadModel(JdbcClient jdbcClient) {
        return new JdbcTripStoreReadModel(jdbcClient);
    }

    @Bean
    JdbcInviteReadModel jdbcInviteReadModel(JdbcClient jdbcClient, Clock clock) {
        return new JdbcInviteReadModel(jdbcClient, clock);
    }

    @Bean
    HouseholdReadModelProjector householdReadModelProjector(
            KurrentDBClient kurrentDbClient,
            JdbcHouseholdReadModel jdbcHouseholdReadModel,
            JdbcStoreReadModel jdbcStoreReadModel,
            JdbcInviteReadModel jdbcInviteReadModel,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new HouseholdReadModelProjector(
                kurrentDbClient, jdbcHouseholdReadModel, jdbcStoreReadModel, jdbcInviteReadModel, autoStart);
    }

    @Bean
    ShoppingListReadModelProjector shoppingListReadModelProjector(
            KurrentDBClient kurrentDbClient,
            JdbcShoppingListReadModel jdbcShoppingListReadModel,
            JdbcItemReadModel jdbcItemReadModel,
            JdbcItemSuggestionReadModel jdbcItemSuggestionReadModel,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new ShoppingListReadModelProjector(
                kurrentDbClient,
                jdbcShoppingListReadModel,
                jdbcItemReadModel,
                jdbcItemSuggestionReadModel,
                autoStart);
    }

    @Bean
    ShoppingTripReadModelProjector shoppingTripReadModelProjector(
            KurrentDBClient kurrentDbClient,
            JdbcTripStoreReadModel jdbcTripStoreReadModel,
            @Value("${sgart.projector.auto-start:false}") boolean autoStart) {
        return new ShoppingTripReadModelProjector(kurrentDbClient, jdbcTripStoreReadModel, autoStart);
    }
}

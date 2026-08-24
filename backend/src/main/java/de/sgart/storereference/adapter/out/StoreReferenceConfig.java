package de.sgart.storereference.adapter.out;

import de.sgart.storereference.application.ListStoreChains;
import de.sgart.storereference.domain.StoreChainReferenceReadModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the Store Reference context's read-only reference read model and its {@code ListStoreChains}
 * query (Story 1.8, AC2). Lives in {@code adapter.out} because it references the domain-owned {@link
 * StoreChainReferenceReadModel} port. Building these beans performs no I/O, so {@code contextLoads()}
 * survives Postgres being down (mirrors {@code HouseholdReadModelConfig}).
 */
@Configuration
public class StoreReferenceConfig {

    @Bean
    StoreChainReferenceReadModel storeChainReferenceReadModel(JdbcClient jdbcClient) {
        return new JdbcStoreChainReferenceReadModel(jdbcClient);
    }

    @Bean
    ListStoreChains listStoreChains(StoreChainReferenceReadModel storeChainReferenceReadModel) {
        return new ListStoreChains(storeChainReferenceReadModel);
    }
}

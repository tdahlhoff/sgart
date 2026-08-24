package de.sgart.storereference.adapter.out;

import de.sgart.shared.StoreChainId;
import de.sgart.storereference.domain.StoreChainReference;
import de.sgart.storereference.domain.StoreChainReferenceReadModel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable, read-only PostgreSQL store-chain reference read model (Story 1.8, AC2). Seeded by
 * {@code db/migration/V3__store_chain_reference.sql} — never written at runtime (AD-4). Plain SQL
 * over {@link JdbcClient}, mirroring {@code JdbcHouseholdReadModel}. Building the bean performs no
 * I/O, so {@code contextLoads()} survives Postgres being down; only the first query connects.
 */
public final class JdbcStoreChainReferenceReadModel implements StoreChainReferenceReadModel {

    private final JdbcClient jdbcClient;

    public JdbcStoreChainReferenceReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<StoreChainReference> listAll() {
        return jdbcClient
                .sql("SELECT chain_id, name FROM store_chain_reference ORDER BY name")
                .query((resultSet, rowNumber) -> new StoreChainReference(
                        new StoreChainId(resultSet.getObject("chain_id", UUID.class)),
                        resultSet.getString("name")))
                .list();
    }
}

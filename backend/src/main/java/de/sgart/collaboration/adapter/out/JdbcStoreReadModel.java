package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.query.ListStores;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.readmodel.StoreReadModel;
import de.sgart.collaboration.domain.readmodel.StoreView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL store read model (Story 1.8): written only by {@link
 * HouseholdReadModelProjector} (AD-4, "read models are projection-only"), read by {@code
 * ListStores} through the {@link StoreReadModel} port it implements. The query returns
 * <strong>active stores only</strong> (the AC5 structural guarantee). Schema: {@code
 * db/migration/V4__store_read_model.sql}. Mirrors {@link JdbcHouseholdReadModel}.
 */
public final class JdbcStoreReadModel implements StoreReadModel {

    private final JdbcClient jdbcClient;

    public JdbcStoreReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<StoreView> activeStoresOf(HouseholdId householdId) {
        return jdbcClient
                .sql("""
                        SELECT store_id, name, chain_id FROM store_read_model
                        WHERE household_id = :householdId AND archived = false
                        """)
                .param("householdId", householdId.value())
                .query((resultSet, rowNumber) -> {
                    UUID chainId = resultSet.getObject("chain_id", UUID.class);
                    return new StoreView(
                            new StoreId(resultSet.getObject("store_id", UUID.class)),
                            new StoreName(resultSet.getString("name")),
                            chainId == null ? null : new StoreChainId(chainId));
                })
                .list();
    }

    /** Idempotent upsert — re-projecting the same {@code StoreAdded} is a safe no-op. */
    void upsertStore(HouseholdId householdId, StoreId storeId, StoreName name, StoreChainId chainId) {
        jdbcClient
                .sql("""
                        INSERT INTO store_read_model (household_id, store_id, name, chain_id, archived)
                        VALUES (:householdId, :storeId, :name, :chainId, false)
                        ON CONFLICT (household_id, store_id)
                        DO UPDATE SET name = EXCLUDED.name, chain_id = EXCLUDED.chain_id
                        """)
                .param("householdId", householdId.value())
                .param("storeId", storeId.value())
                .param("name", name.value())
                .param("chainId", chainId == null ? null : chainId.value())
                .update();
    }

    /** Idempotent flag flip — re-projecting the same {@code StoreArchived} is a safe no-op. */
    void markArchived(HouseholdId householdId, StoreId storeId) {
        jdbcClient
                .sql("""
                        UPDATE store_read_model SET archived = true
                        WHERE household_id = :householdId AND store_id = :storeId
                        """)
                .param("householdId", householdId.value())
                .param("storeId", storeId.value())
                .update();
    }
}

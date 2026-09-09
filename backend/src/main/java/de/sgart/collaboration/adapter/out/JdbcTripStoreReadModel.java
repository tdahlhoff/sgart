package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL trip-store read model (Story 3.2, Cl. 4): written only by {@link
 * ShoppingTripReadModelProjector} (AD-4, "read models are projection-only"), read by {@code
 * TripView} through the {@link TripStoreReadModel} port it implements. Schema: {@code
 * db/migration/V9__trip_read_model.sql}. Mirrors {@link JdbcItemReadModel}.
 */
public final class JdbcTripStoreReadModel implements TripStoreReadModel {

    private final JdbcClient jdbcClient;

    public JdbcTripStoreReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /** Idempotent upsert — re-projecting the same event is a genuine no-op ({@code DO NOTHING}). */
    @Override
    public void addStore(HouseholdId householdId, TripId tripId, StoreId storeId) {
        jdbcClient
                .sql("""
                        INSERT INTO trip_store_read_model (household_id, trip_id, store_id)
                        VALUES (:householdId, :tripId, :storeId)
                        ON CONFLICT (trip_id, store_id) DO NOTHING
                        """)
                .param("householdId", householdId.value())
                .param("tripId", tripId.value())
                .param("storeId", storeId.value())
                .update();
    }

    @Override
    public List<StoreId> storesOf(TripId tripId) {
        return jdbcClient
                .sql("""
                        SELECT store_id FROM trip_store_read_model
                        WHERE trip_id = :tripId
                        ORDER BY sequence_number ASC
                        """)
                .param("tripId", tripId.value())
                .query((resultSet, rowNumber) -> StoreId.fromString(resultSet.getString("store_id")))
                .list();
    }

    /** Idempotent delete — re-projecting the same {@code TripCompleted} is a safe no-op. */
    @Override
    public void deleteForTrip(TripId tripId) {
        jdbcClient
                .sql("DELETE FROM trip_store_read_model WHERE trip_id = :tripId")
                .param("tripId", tripId.value())
                .update();
    }

    /**
     * The trip-scoped live-sync resolver lookup (Story 4.4, T4) — cached by the caller. Any one
     * store row for the trip carries the same {@code household_id} (the column exists solely so
     * {@link #purgeHousehold} can target a household's rows directly), so the first row suffices.
     */
    @Override
    public Optional<HouseholdId> householdIdOfTrip(TripId tripId) {
        return jdbcClient
                .sql("SELECT household_id FROM trip_store_read_model WHERE trip_id = :tripId LIMIT 1")
                .param("tripId", tripId.value())
                .query((resultSet, rowNumber) -> HouseholdId.fromString(resultSet.getString("household_id")))
                .optional();
    }

    /** Idempotent bulk delete — the delete-cascade purge (Story 4.3, AC7, decision 4). */
    void purgeHousehold(HouseholdId householdId) {
        jdbcClient
                .sql("DELETE FROM trip_store_read_model WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .update();
    }
}

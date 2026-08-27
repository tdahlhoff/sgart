package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL shopping-list read model (Story 2.1): written only by {@link
 * ShoppingListReadModelProjector} (AD-4, "read models are projection-only"), read by {@code
 * ListOpenLists} through the {@link ShoppingListReadModel} port it implements. Schema: {@code
 * db/migration/V5__shopping_list_read_model.sql}. Mirrors {@link JdbcStoreReadModel}.
 */
public final class JdbcShoppingListReadModel implements ShoppingListReadModel {

    private final JdbcClient jdbcClient;

    public JdbcShoppingListReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<ShoppingListView> listsOf(HouseholdId householdId) {
        return jdbcClient
                .sql("""
                        SELECT l.list_id, l.name, l.status, COUNT(i.item_id) AS item_count
                        FROM shopping_list_read_model l
                        LEFT JOIN item_read_model i ON i.list_id = l.list_id
                        WHERE l.household_id = :householdId
                        GROUP BY l.list_id, l.name, l.status, l.sequence_number
                        ORDER BY l.sequence_number ASC
                        """)
                .param("householdId", householdId.value())
                .query((resultSet, rowNumber) -> {
                    String name = resultSet.getString("name");
                    return new ShoppingListView(
                            ShoppingListId.fromString(resultSet.getString("list_id")),
                            name == null ? null : new ShoppingListName(name),
                            ListStatus.valueOf(resultSet.getString("status")),
                            resultSet.getInt("item_count"));
                })
                .list();
    }

    /**
     * Idempotent insert — re-projecting the same {@code ShoppingListCreated} is a genuine no-op
     * ({@code DO NOTHING}): the row's mutable {@code name} is owned solely by {@link #renameList}
     * (the aggregate raises a rename for every later name change), so a {@code fromStart} replay must
     * not reset {@code name} back to the creation-time value between the replayed {@code Created} and
     * {@code Renamed}. The {@code sequence_number}/{@code created_at} columns are likewise never
     * rewritten, so the creation order stays stable across re-projection.
     */
    void insertList(HouseholdId householdId, ShoppingListId listId, ShoppingListName name) {
        jdbcClient
                .sql("""
                        INSERT INTO shopping_list_read_model (list_id, household_id, name, status)
                        VALUES (:listId, :householdId, :name, :status)
                        ON CONFLICT (list_id) DO NOTHING
                        """)
                .param("listId", listId.value())
                .param("householdId", householdId.value())
                .param("name", name == null ? null : name.value())
                .param("status", ListStatus.OPEN.name())
                .update();
    }

    /** Idempotent update — re-projecting the same {@code ShoppingListRenamed} is a safe no-op. */
    void renameList(ShoppingListId listId, ShoppingListName newName) {
        jdbcClient
                .sql("UPDATE shopping_list_read_model SET name = :name WHERE list_id = :listId")
                .param("listId", listId.value())
                .param("name", newName.value())
                .update();
    }
}

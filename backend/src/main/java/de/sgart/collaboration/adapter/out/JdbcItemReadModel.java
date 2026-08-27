package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL item read model (Story 2.3): written only by {@link
 * ShoppingListReadModelProjector} (AD-4, "read models are projection-only"), read by {@code
 * ListItems} through the {@link ItemReadModel} port it implements. Schema: {@code
 * db/migration/V6__item_read_model.sql}. Mirrors {@link JdbcShoppingListReadModel}.
 */
public final class JdbcItemReadModel implements ItemReadModel {

    private final JdbcClient jdbcClient;

    public JdbcItemReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId) {
        return jdbcClient
                .sql("""
                        SELECT item_id, name, note, quantity_amount, quantity_unit FROM item_read_model
                        WHERE household_id = :householdId AND list_id = :listId
                        ORDER BY sequence_number ASC
                        """)
                .param("householdId", householdId.value())
                .param("listId", listId.value())
                .query((resultSet, rowNumber) -> {
                    String note = resultSet.getString("note");
                    return new ItemView(
                            ItemId.fromString(resultSet.getString("item_id")),
                            new ItemName(resultSet.getString("name")),
                            note == null ? null : new ItemNote(note),
                            new Quantity(
                                    resultSet.getBigDecimal("quantity_amount"),
                                    Unit.valueOf(resultSet.getString("quantity_unit"))));
                })
                .list();
    }

    @Override
    public Optional<HouseholdId> householdIdOf(ItemId itemId) {
        return jdbcClient
                .sql("SELECT household_id FROM item_read_model WHERE item_id = :itemId")
                .param("itemId", itemId.value())
                .query((resultSet, rowNumber) -> HouseholdId.fromString(resultSet.getString("household_id")))
                .optional();
    }

    /** Idempotent insert — re-projecting the same {@code ItemAdded} is a genuine no-op ({@code DO NOTHING}). */
    void insertItem(
            HouseholdId householdId,
            ShoppingListId listId,
            ItemId itemId,
            ItemName name,
            ItemNote note,
            Quantity quantity) {
        jdbcClient
                .sql("""
                        INSERT INTO item_read_model
                            (item_id, list_id, household_id, name, note, quantity_amount, quantity_unit)
                        VALUES (:itemId, :listId, :householdId, :name, :note, :amount, :unit)
                        ON CONFLICT (item_id) DO NOTHING
                        """)
                .param("itemId", itemId.value())
                .param("listId", listId.value())
                .param("householdId", householdId.value())
                .param("name", name.value())
                .param("note", note == null ? null : note.value())
                .param("amount", quantity.amount())
                .param("unit", quantity.unit().name())
                .update();
    }

    /** Idempotent update — re-projecting the same {@code ItemUpdated} is a safe no-op. */
    void updateItem(ItemId itemId, ItemName name, ItemNote note, Quantity quantity) {
        jdbcClient
                .sql("""
                        UPDATE item_read_model
                        SET name = :name, note = :note, quantity_amount = :amount, quantity_unit = :unit
                        WHERE item_id = :itemId
                        """)
                .param("itemId", itemId.value())
                .param("name", name.value())
                .param("note", note == null ? null : note.value())
                .param("amount", quantity.amount())
                .param("unit", quantity.unit().name())
                .update();
    }

    /** Idempotent delete — re-projecting the same {@code ItemRemoved} is a safe no-op. */
    void removeItem(ItemId itemId) {
        jdbcClient.sql("DELETE FROM item_read_model WHERE item_id = :itemId").param("itemId", itemId.value()).update();
    }
}

package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
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
                        SELECT item_id, name, note, quantity_amount, quantity_unit, store_id, status, transfer_pending
                        FROM item_read_model
                        WHERE household_id = :householdId AND list_id = :listId
                        ORDER BY sequence_number ASC
                        """)
                .param("householdId", householdId.value())
                .param("listId", listId.value())
                .query((resultSet, rowNumber) -> {
                    String note = resultSet.getString("note");
                    String storeId = resultSet.getString("store_id");
                    return new ItemView(
                            ItemId.fromString(resultSet.getString("item_id")),
                            new ItemName(resultSet.getString("name")),
                            note == null ? null : new ItemNote(note),
                            new Quantity(
                                    resultSet.getBigDecimal("quantity_amount"),
                                    Unit.valueOf(resultSet.getString("quantity_unit"))),
                            storeId == null ? null : StoreId.fromString(storeId),
                            ItemStatus.valueOf(resultSet.getString("status")),
                            resultSet.getBoolean("transfer_pending"));
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

    @Override
    public void assignStore(ItemId itemId, StoreId storeId) {
        jdbcClient
                .sql("UPDATE item_read_model SET store_id = :storeId WHERE item_id = :itemId")
                .param("itemId", itemId.value())
                .param("storeId", storeId.value())
                .update();
    }

    @Override
    public Optional<ItemName> nameOf(ItemId itemId) {
        return jdbcClient
                .sql("SELECT name FROM item_read_model WHERE item_id = :itemId")
                .param("itemId", itemId.value())
                .query((resultSet, rowNumber) -> new ItemName(resultSet.getString("name")))
                .optional();
    }

    /**
     * Idempotent insert — re-projecting the same {@code ItemAdded} for the item's <em>current</em>
     * list is a genuine no-op. Also doubles as the Story 3.6 transfer's target-side relocation: when
     * this {@code ItemAdded} names an {@code itemId} that already has a row under a
     * <strong>different</strong> {@code list_id} (the source, still reserved there — {@link
     * ShoppingListReadModelProjector} processes {@code ItemTransferInitiated} before this, per the
     * saga's own append order), the {@code DO UPDATE} branch relocates the row to this list: new
     * {@code list_id}/{@code household_id}/name/note/quantity, {@code transfer_pending} cleared,
     * {@code store_id}/{@code status} reset to the item's birth state (unassigned, {@code OPEN} —
     * the target aggregate adds it with a bare {@code ItemAdded}, so a source-side assignment or
     * in-trip status must not survive the move), and a <strong>fresh</strong> {@code sequence_number}
     * so the item appends at the target's tail (matching the pre-3.6 delete-then-reinsert behavior)
     * rather than keeping its old position. The
     * {@code WHERE} guard on the conflict action is what tells the two cases apart: a same-list
     * replay leaves the guard false (no changes, true idempotency); a cross-list arrival leaves it
     * true (relocate). The source row itself is only removed later, by {@code
     * ItemTransferConfirmed}'s list-scoped {@link #removeItem}.
     */
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
                        ON CONFLICT (item_id) DO UPDATE SET
                            list_id = EXCLUDED.list_id,
                            household_id = EXCLUDED.household_id,
                            name = EXCLUDED.name,
                            note = EXCLUDED.note,
                            quantity_amount = EXCLUDED.quantity_amount,
                            quantity_unit = EXCLUDED.quantity_unit,
                            transfer_pending = FALSE,
                            store_id = NULL,
                            status = 'OPEN',
                            sequence_number = nextval(pg_get_serial_sequence('item_read_model', 'sequence_number')),
                            created_at = now()
                        WHERE item_read_model.list_id <> EXCLUDED.list_id
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

    @Override
    public void setStatus(ItemId itemId, ItemStatus status) {
        jdbcClient
                .sql("UPDATE item_read_model SET status = :status WHERE item_id = :itemId")
                .param("itemId", itemId.value())
                .param("status", status.name())
                .update();
    }

    @Override
    public void setTransferPending(ItemId itemId, boolean pending) {
        jdbcClient
                .sql("UPDATE item_read_model SET transfer_pending = :pending WHERE item_id = :itemId")
                .param("itemId", itemId.value())
                .param("pending", pending)
                .update();
    }

    /**
     * Idempotent, list-scoped delete — re-projecting the same {@code ItemRemoved}/{@code
     * ItemTransferConfirmed} is a safe no-op. Scoped to {@code (item_id, list_id)}, not {@code
     * item_id} alone (Story 3.6): once a transfer's target-side {@link #insertItem} has relocated
     * the row to the target list, the source's {@code ItemTransferConfirmed} names the item's
     * <em>old</em> {@code list_id} — the scoped {@code WHERE} makes that a no-op instead of deleting
     * the item's new row out from under the target.
     */
    void removeItem(ItemId itemId, ShoppingListId listId) {
        jdbcClient
                .sql("DELETE FROM item_read_model WHERE item_id = :itemId AND list_id = :listId")
                .param("itemId", itemId.value())
                .param("listId", listId.value())
                .update();
    }
}

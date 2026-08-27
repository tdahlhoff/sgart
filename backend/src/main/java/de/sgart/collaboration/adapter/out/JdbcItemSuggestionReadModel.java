package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionReadModel;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.Quantity;
import de.sgart.shared.Unit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL item suggestion read model (Story 2.5): written only by {@link
 * ShoppingListReadModelProjector} (AD-4), read by {@code ListItemSuggestions} through the {@link
 * ItemSuggestionReadModel} port it implements. Schema: {@code
 * db/migration/V7__item_suggestion_read_model.sql}. Mirrors {@link JdbcItemReadModel}, but is
 * upsert-only — never deleted (Cl. 1).
 */
public final class JdbcItemSuggestionReadModel implements ItemSuggestionReadModel {

    private final JdbcClient jdbcClient;

    public JdbcItemSuggestionReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<ItemSuggestionView> suggestionsOf(HouseholdId householdId) {
        return jdbcClient
                .sql("""
                        SELECT name, note, quantity_amount, quantity_unit FROM item_suggestion_read_model
                        WHERE household_id = :householdId
                        ORDER BY name ASC
                        """)
                .param("householdId", householdId.value())
                .query((resultSet, rowNumber) -> {
                    String note = resultSet.getString("note");
                    return new ItemSuggestionView(
                            new ItemName(resultSet.getString("name")),
                            note == null ? null : new ItemNote(note),
                            new Quantity(
                                    resultSet.getBigDecimal("quantity_amount"),
                                    Unit.valueOf(resultSet.getString("quantity_unit"))));
                })
                .list();
    }

    /**
     * Upsert keyed by {@code (household_id, normalized_name)} — records the name's last-used
     * display casing and attributes (Cl. 6). Called for both {@code ItemAdded} and {@code
     * ItemUpdated}; never for {@code ItemRemoved}/{@code ItemMovedToList} (history survives, Cl. 1).
     */
    void recordUsage(HouseholdId householdId, ItemName name, ItemNote note, Quantity quantity) {
        String normalizedName = name.value().trim().toLowerCase(Locale.ROOT);
        jdbcClient
                .sql("""
                        INSERT INTO item_suggestion_read_model
                            (household_id, normalized_name, name, note, quantity_amount, quantity_unit)
                        VALUES (:householdId, :normalizedName, :displayName, :note, :amount, :unit)
                        ON CONFLICT (household_id, normalized_name)
                        DO UPDATE SET name = :displayName, note = :note,
                            quantity_amount = :amount, quantity_unit = :unit
                        """)
                .param("householdId", householdId.value())
                .param("normalizedName", normalizedName)
                .param("displayName", name.value())
                .param("note", note == null ? null : note.value())
                .param("amount", quantity.amount())
                .param("unit", quantity.unit().name())
                .update();
    }
}

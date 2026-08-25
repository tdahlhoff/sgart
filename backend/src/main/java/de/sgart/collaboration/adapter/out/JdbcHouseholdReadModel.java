package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.query.ListMyHouseholds;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.readmodel.HouseholdNameReadModel;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL household read model (Story 1.6, first CQRS read side): written only by
 * {@link HouseholdReadModelProjector} (AD-4, "read models are projection-only"), read by {@code
 * ListMyHouseholds} through the {@link HouseholdNameReadModel} port it implements. Schema: {@code
 * db/migration/V2__household_read_model.sql}.
 */
public final class JdbcHouseholdReadModel implements HouseholdNameReadModel {

    private final JdbcClient jdbcClient;

    public JdbcHouseholdReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Map<HouseholdId, HouseholdName> namesFor(List<HouseholdId> householdIds) {
        if (householdIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> rawIds = householdIds.stream().map(HouseholdId::value).toList();
        return jdbcClient
                .sql("SELECT household_id, name FROM household_read_model WHERE household_id IN (:householdIds)")
                .param("householdIds", rawIds)
                .query((resultSet, rowNumber) -> Map.entry(
                        new HouseholdId(resultSet.getObject("household_id", UUID.class)),
                        new HouseholdName(resultSet.getString("name"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Idempotent upsert — re-projecting the same {@code HouseholdCreated} is a safe no-op. */
    void upsertHousehold(HouseholdId householdId, HouseholdName name) {
        jdbcClient
                .sql("""
                        INSERT INTO household_read_model (household_id, name) VALUES (:householdId, :name)
                        ON CONFLICT (household_id) DO UPDATE SET name = EXCLUDED.name
                        """)
                .param("householdId", householdId.value())
                .param("name", name.value())
                .update();
    }

    /** Idempotent insert — re-projecting the same {@code MemberJoined} is a safe no-op. */
    void addMember(HouseholdId householdId, MemberId memberId) {
        jdbcClient
                .sql("""
                        INSERT INTO household_membership_read_model (household_id, member_id)
                        VALUES (:householdId, :memberId)
                        ON CONFLICT (household_id, member_id) DO NOTHING
                        """)
                .param("householdId", householdId.value())
                .param("memberId", memberId.value())
                .update();
    }
}

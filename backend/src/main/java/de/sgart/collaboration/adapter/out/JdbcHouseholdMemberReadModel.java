package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL member-roster read model (Story 4.3, AC8): written only by {@link
 * HouseholdReadModelProjector} (AD-4), read by {@code ListHouseholdMembers} through the {@link
 * HouseholdMemberReadModel} port it implements. No PII column (AD-6, decision 5) — only {@code
 * (household_id, member_id, role)}. Schema: {@code db/migration/V14__household_member_read_model.sql}.
 * Mirrors {@link JdbcInviteReadModel}.
 */
public final class JdbcHouseholdMemberReadModel implements HouseholdMemberReadModel {

    private final JdbcClient jdbcClient;

    public JdbcHouseholdMemberReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public List<MemberRoleView> membersOf(HouseholdId householdId) {
        return jdbcClient
                .sql("SELECT member_id, role FROM household_member_read_model WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .query((resultSet, rowNumber) -> new MemberRoleView(
                        new MemberId(resultSet.getObject("member_id", UUID.class)),
                        HouseholdRole.valueOf(resultSet.getString("role"))))
                .list();
    }

    /** Idempotent upsert — re-projecting the same join/promote/demote is a safe no-op. */
    void upsert(HouseholdId householdId, MemberId memberId, HouseholdRole role) {
        jdbcClient
                .sql("""
                        INSERT INTO household_member_read_model (household_id, member_id, role)
                        VALUES (:householdId, :memberId, :role)
                        ON CONFLICT (household_id, member_id) DO UPDATE SET role = EXCLUDED.role
                        """)
                .param("householdId", householdId.value())
                .param("memberId", memberId.value())
                .param("role", role.name())
                .update();
    }

    /** Idempotent delete — re-projecting the same leave/remove is a safe no-op. */
    void remove(HouseholdId householdId, MemberId memberId) {
        jdbcClient
                .sql("DELETE FROM household_member_read_model WHERE household_id = :householdId AND member_id = :memberId")
                .param("householdId", householdId.value())
                .param("memberId", memberId.value())
                .update();
    }

    /** Idempotent bulk delete — the delete-cascade purge (Story 4.3, AC7, decision 4). */
    void purgeHousehold(HouseholdId householdId) {
        jdbcClient
                .sql("DELETE FROM household_member_read_model WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .update();
    }
}

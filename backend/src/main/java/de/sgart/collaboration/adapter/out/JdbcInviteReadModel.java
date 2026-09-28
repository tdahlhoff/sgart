package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL read model for a household's single active invite code (Story 8.4, F7):
 * written only by {@link HouseholdReadModelProjector} (AD-4), read by {@code GetActiveInviteCode}
 * through the {@link InviteReadModel} port it implements. One row per household — replacing the
 * code overwrites it in place; no TTL, no status column (the code is either the active one or it no
 * longer exists in this table at all). Schema: {@code db/migration/V23__single_household_invite_code.sql}.
 * Mirrors {@link JdbcStoreReadModel}.
 */
public final class JdbcInviteReadModel implements InviteReadModel {

    private final JdbcClient jdbcClient;

    public JdbcInviteReadModel(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<InviteView> activeInviteOf(HouseholdId householdId) {
        return jdbcClient
                .sql("SELECT invite_id FROM household_invite_code WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .query((resultSet, rowNumber) ->
                        new InviteView(new InviteId(resultSet.getObject("invite_id", UUID.class))))
                .optional();
    }

    /** Idempotent upsert — re-projecting the same {@code MemberInvited} (issue or replace) is a
     * safe no-op/overwrite either way. */
    void upsertActiveInvite(HouseholdId householdId, InviteId inviteId, Instant issuedAt) {
        jdbcClient
                .sql("""
                        INSERT INTO household_invite_code (household_id, invite_id, updated_at)
                        VALUES (:householdId, :inviteId, :updatedAt)
                        ON CONFLICT (household_id) DO UPDATE SET
                            invite_id = EXCLUDED.invite_id, updated_at = EXCLUDED.updated_at
                        """)
                .param("householdId", householdId.value())
                .param("inviteId", inviteId.value())
                .param("updatedAt", Timestamp.from(issuedAt))
                .update();
    }

    /** Idempotent bulk delete — the delete-cascade purge (Story 8.4). */
    void purgeHousehold(HouseholdId householdId) {
        jdbcClient
                .sql("DELETE FROM household_invite_code WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .update();
    }
}

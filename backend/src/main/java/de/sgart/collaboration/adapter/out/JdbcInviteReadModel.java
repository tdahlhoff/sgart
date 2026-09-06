package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.Invite;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable PostgreSQL invite read model (Story 4.1, AC6): written only by {@link
 * HouseholdReadModelProjector} (AD-4), read by {@code ListPendingInvites} through the {@link
 * InviteReadModel} port it implements. Returns <strong>pending (non-expired) invites only</strong>
 * — "expired" is derived from {@code invited_at + TTL} at query time, matching the aggregate's own
 * lazy-expiry semantics (AC5) rather than needing a scheduled job. No email/HMAC column (AD-6).
 * Schema: {@code db/migration/V12__invite_read_model.sql}. Mirrors {@link JdbcStoreReadModel}.
 */
public final class JdbcInviteReadModel implements InviteReadModel {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public JdbcInviteReadModel(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<InviteView> pendingInvitesOf(HouseholdId householdId) {
        Instant notExpiredAfter = clock.instant().minus(Invite.TIME_TO_LIVE);
        return jdbcClient
                .sql("""
                        SELECT invite_id, status, invited_at, invited_by FROM invite_read_model
                        WHERE household_id = :householdId AND status = 'PENDING' AND invited_at > :notExpiredAfter
                        ORDER BY invited_at DESC
                        """)
                .param("householdId", householdId.value())
                .param("notExpiredAfter", Timestamp.from(notExpiredAfter))
                .query((resultSet, rowNumber) -> new InviteView(
                        new InviteId(resultSet.getObject("invite_id", UUID.class)),
                        resultSet.getTimestamp("invited_at").toInstant(),
                        new MemberId(resultSet.getObject("invited_by", UUID.class)),
                        resultSet.getString("status")))
                .list();
    }

    /** Idempotent upsert — re-projecting the same {@code MemberInvited} is a safe no-op. */
    void upsertInvite(HouseholdId householdId, InviteId inviteId, MemberId invitedBy, Instant invitedAt) {
        jdbcClient
                .sql("""
                        INSERT INTO invite_read_model (household_id, invite_id, status, invited_at, invited_by)
                        VALUES (:householdId, :inviteId, 'PENDING', :invitedAt, :invitedBy)
                        ON CONFLICT (household_id, invite_id) DO NOTHING
                        """)
                .param("householdId", householdId.value())
                .param("inviteId", inviteId.value())
                .param("invitedAt", Timestamp.from(invitedAt))
                .param("invitedBy", invitedBy.value())
                .update();
    }

    /** Idempotent flag flip — re-projecting the same {@code InviteExpired} is a safe no-op. */
    void markExpired(HouseholdId householdId, InviteId inviteId) {
        jdbcClient
                .sql("""
                        UPDATE invite_read_model SET status = 'EXPIRED'
                        WHERE household_id = :householdId AND invite_id = :inviteId
                        """)
                .param("householdId", householdId.value())
                .param("inviteId", inviteId.value())
                .update();
    }
}

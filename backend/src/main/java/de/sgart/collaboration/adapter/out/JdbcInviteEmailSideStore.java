package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.shared.InviteId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The durable {@code invite_email_side_store} adapter (Story 4.1, AD-6, locked decision 3) — the
 * only place a raw invite email is persisted. Plain {@code JdbcClient} over a mutable table (KISS),
 * mirroring {@code JdbcMemberMappingRepository}. Schema: {@code
 * db/migration/V13__invite_email_side_store.sql}.
 */
public final class JdbcInviteEmailSideStore implements InviteEmailSideStore {

    private final JdbcClient jdbcClient;

    public JdbcInviteEmailSideStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void store(InviteId inviteId, NormalizedEmail email) {
        jdbcClient
                .sql("""
                        INSERT INTO invite_email_side_store (invite_id, normalized_email)
                        VALUES (:inviteId, :normalizedEmail)
                        ON CONFLICT (invite_id) DO UPDATE SET normalized_email = EXCLUDED.normalized_email
                        """)
                .param("inviteId", inviteId.value())
                .param("normalizedEmail", email.value())
                .update();
    }

    @Override
    public void purge(InviteId inviteId) {
        jdbcClient
                .sql("DELETE FROM invite_email_side_store WHERE invite_id = :inviteId")
                .param("inviteId", inviteId.value())
                .update();
    }

    @Override
    public Optional<NormalizedEmail> findEmail(InviteId inviteId) {
        return jdbcClient
                .sql("SELECT normalized_email FROM invite_email_side_store WHERE invite_id = :inviteId")
                .param("inviteId", inviteId.value())
                .query(String.class)
                .optional()
                .map(NormalizedEmail::new);
    }
}

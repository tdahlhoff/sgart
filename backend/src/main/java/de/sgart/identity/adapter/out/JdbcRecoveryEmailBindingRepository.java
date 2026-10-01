package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingExport;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link RecoveryEmailBindingRepository}. Plain SQL over {@link JdbcClient}.
 * Schema: {@code db/migration/V24__recovery_email_binding.sql}. Holds a digest and a masked hint
 * only, never the address.
 */
public final class JdbcRecoveryEmailBindingRepository implements RecoveryEmailBindingRepository {

    private static final String SELECT_COLUMNS =
            "SELECT address_digest, keycloak_user_id, address_hint, confirmed_at, created_at FROM recovery_email_binding";

    private final JdbcClient jdbcClient;

    public JdbcRecoveryEmailBindingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void savePending(RecoveryEmailBinding pendingBinding) {
        jdbcClient
                .sql("DELETE FROM recovery_email_binding WHERE keycloak_user_id = :keycloakUserId"
                        + " AND confirmed_at IS NULL")
                .param("keycloakUserId", pendingBinding.keycloakUserId().value())
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO recovery_email_binding
                            (address_digest, keycloak_user_id, address_hint, confirmed_at, created_at)
                        VALUES (:digest, :keycloakUserId, :hint, NULL, :createdAt)
                        ON CONFLICT (address_digest, keycloak_user_id) DO NOTHING
                        """)
                .param("digest", pendingBinding.digest().value())
                .param("keycloakUserId", pendingBinding.keycloakUserId().value())
                .param("hint", pendingBinding.hint().value())
                .param("createdAt", Timestamp.from(pendingBinding.createdAt()))
                .update();
    }

    @Override
    public Optional<RecoveryEmailBinding> findPendingFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " WHERE keycloak_user_id = :keycloakUserId AND confirmed_at IS NULL")
                .param("keycloakUserId", keycloakUserId.value())
                .query(JdbcRecoveryEmailBindingRepository::mapRow)
                .optional();
    }

    @Override
    public void confirm(RecoveryEmailBinding confirmedBinding) {
        jdbcClient
                .sql("DELETE FROM recovery_email_binding WHERE keycloak_user_id = :keycloakUserId"
                        + " AND confirmed_at IS NOT NULL AND address_digest <> :digest")
                .param("keycloakUserId", confirmedBinding.keycloakUserId().value())
                .param("digest", confirmedBinding.digest().value())
                .update();
        jdbcClient
                .sql("""
                        UPDATE recovery_email_binding SET confirmed_at = :confirmedAt
                        WHERE address_digest = :digest AND keycloak_user_id = :keycloakUserId
                        """)
                .param("confirmedAt", Timestamp.from(confirmedBinding.confirmedAt()))
                .param("digest", confirmedBinding.digest().value())
                .param("keycloakUserId", confirmedBinding.keycloakUserId().value())
                .update();
    }

    @Override
    public List<RecoveryEmailBinding> findConfirmedFor(RecoveryEmailDigest digest) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " WHERE address_digest = :digest AND confirmed_at IS NOT NULL")
                .param("digest", digest.value())
                .query(JdbcRecoveryEmailBindingRepository::mapRow)
                .list();
    }

    @Override
    public Optional<RecoveryEmailBinding> findConfirmedFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " WHERE keycloak_user_id = :keycloakUserId AND confirmed_at IS NOT NULL")
                .param("keycloakUserId", keycloakUserId.value())
                .query(JdbcRecoveryEmailBindingRepository::mapRow)
                .optional();
    }

    @Override
    public boolean hasConfirmedBindingFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql("SELECT EXISTS (SELECT 1 FROM recovery_email_binding"
                        + " WHERE keycloak_user_id = :keycloakUserId AND confirmed_at IS NOT NULL)")
                .param("keycloakUserId", keycloakUserId.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void deleteAllFor(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM recovery_email_binding WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .update();
    }

    @Override
    public List<RecoveryEmailBindingExport> findAllFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql("SELECT address_hint, confirmed_at FROM recovery_email_binding"
                        + " WHERE keycloak_user_id = :keycloakUserId ORDER BY created_at")
                .param("keycloakUserId", keycloakUserId.value())
                .query((resultSet, rowNumber) -> new RecoveryEmailBindingExport(
                        new RecoveryEmailHint(resultSet.getString("address_hint")), readInstant(resultSet, "confirmed_at")))
                .list();
    }

    @Override
    public void deletePendingCreatedBefore(Instant threshold) {
        jdbcClient
                .sql("DELETE FROM recovery_email_binding WHERE confirmed_at IS NULL AND created_at < :threshold")
                .param("threshold", Timestamp.from(threshold))
                .update();
    }

    private static RecoveryEmailBinding mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RecoveryEmailBinding(
                new RecoveryEmailDigest(resultSet.getString("address_digest")),
                new KeycloakUserId(resultSet.getString("keycloak_user_id")),
                new RecoveryEmailHint(resultSet.getString("address_hint")),
                readInstant(resultSet, "confirmed_at"),
                readInstant(resultSet, "created_at"));
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

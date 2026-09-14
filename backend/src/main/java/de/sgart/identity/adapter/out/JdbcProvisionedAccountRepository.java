package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccount;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link ProvisionedAccountRepository} (Story 7.1). Plain SQL over {@link
 * JdbcClient} — mirrors {@link JdbcMemberMappingRepository}/{@link JdbcDeviceTokenRepository}.
 * Schema: {@code db/migration/V18__provisioned_account.sql}.
 */
public final class JdbcProvisionedAccountRepository implements ProvisionedAccountRepository {

    private final JdbcClient jdbcClient;

    public JdbcProvisionedAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void recordIfAbsent(KeycloakUserId keycloakUserId, Instant provisionedAt) {
        jdbcClient
                .sql("""
                        INSERT INTO provisioned_account (keycloak_user_id, provisioned_at)
                        VALUES (:keycloakUserId, :provisionedAt)
                        ON CONFLICT (keycloak_user_id) DO NOTHING
                        """)
                .param("keycloakUserId", keycloakUserId.value())
                .param("provisionedAt", Timestamp.from(provisionedAt))
                .update();
    }

    @Override
    public List<ProvisionedAccount> findProvisionedBefore(Instant threshold) {
        return jdbcClient
                .sql("""
                        SELECT keycloak_user_id, provisioned_at FROM provisioned_account
                        WHERE provisioned_at < :threshold
                        """)
                .param("threshold", Timestamp.from(threshold))
                .query(JdbcProvisionedAccountRepository::mapRow)
                .list();
    }

    @Override
    public void delete(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM provisioned_account WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .update();
    }

    private static ProvisionedAccount mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProvisionedAccount(
                new KeycloakUserId(resultSet.getString("keycloak_user_id")),
                resultSet.getTimestamp("provisioned_at").toInstant());
    }
}

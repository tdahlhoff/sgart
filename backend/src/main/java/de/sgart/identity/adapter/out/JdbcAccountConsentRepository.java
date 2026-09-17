package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.AccountConsent;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link AccountConsentRepository} (Story 7.4). Plain SQL over {@link
 * JdbcClient} — mirrors {@link JdbcProvisionedAccountRepository}. Schema: {@code
 * db/migration/V20__account_consent.sql}.
 */
public final class JdbcAccountConsentRepository implements AccountConsentRepository {

    private final JdbcClient jdbcClient;

    public JdbcAccountConsentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void record(KeycloakUserId keycloakUserId, String noticeVersion, Instant acceptedAt) {
        jdbcClient
                .sql("""
                        INSERT INTO account_consent (keycloak_user_id, notice_version, accepted_at)
                        VALUES (:keycloakUserId, :noticeVersion, :acceptedAt)
                        ON CONFLICT (keycloak_user_id)
                        DO UPDATE SET notice_version = EXCLUDED.notice_version, accepted_at = EXCLUDED.accepted_at
                        """)
                .param("keycloakUserId", keycloakUserId.value())
                .param("noticeVersion", noticeVersion)
                .param("acceptedAt", Timestamp.from(acceptedAt))
                .update();
    }

    @Override
    public Optional<AccountConsent> findFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql("""
                        SELECT keycloak_user_id, notice_version, accepted_at FROM account_consent
                        WHERE keycloak_user_id = :keycloakUserId
                        """)
                .param("keycloakUserId", keycloakUserId.value())
                .query(JdbcAccountConsentRepository::mapRow)
                .optional();
    }

    @Override
    public void deleteFor(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM account_consent WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .update();
    }

    private static AccountConsent mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccountConsent(
                new KeycloakUserId(resultSet.getString("keycloak_user_id")),
                resultSet.getString("notice_version"),
                resultSet.getTimestamp("accepted_at").toInstant());
    }
}

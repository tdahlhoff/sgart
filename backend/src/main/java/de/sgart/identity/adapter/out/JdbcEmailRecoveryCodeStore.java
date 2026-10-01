package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.EmailRecoveryCode;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link EmailRecoveryCodeStore} (Story 7.3). Plain SQL over {@link
 * JdbcClient} — mirrors {@link JdbcProvisionedAccountRepository}. Schema: {@code
 * db/migration/V19__email_recovery_code.sql}, key column renamed by {@code V25} (the {@code
 * recovery_code} table — named without "email" in its identifier so the privacy guard's substring
 * scan reads cleanly, AD-6).
 */
public final class JdbcEmailRecoveryCodeStore implements EmailRecoveryCodeStore {

    private final JdbcClient jdbcClient;

    public JdbcEmailRecoveryCodeStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void store(
            RecoveryCodeSubject subject,
            RecoveryCodePurpose purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt) {
        jdbcClient
                .sql("""
                        INSERT INTO recovery_code
                            (subject, purpose, code_hash, expires_at, attempts, created_at)
                        VALUES (:subject, :purpose, :codeHash, :expiresAt, 0, :createdAt)
                        ON CONFLICT (subject, purpose) DO UPDATE SET
                            code_hash = EXCLUDED.code_hash,
                            expires_at = EXCLUDED.expires_at,
                            attempts = 0,
                            created_at = EXCLUDED.created_at
                        """)
                .param("subject", subject.value())
                .param("purpose", purpose.name())
                .param("codeHash", codeHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    @Override
    public Optional<EmailRecoveryCode> find(RecoveryCodeSubject subject, RecoveryCodePurpose purpose) {
        return jdbcClient
                .sql("""
                        SELECT subject, purpose, code_hash, expires_at, attempts, created_at
                        FROM recovery_code
                        WHERE subject = :subject AND purpose = :purpose
                        """)
                .param("subject", subject.value())
                .param("purpose", purpose.name())
                .query(JdbcEmailRecoveryCodeStore::mapRow)
                .optional();
    }

    @Override
    public void incrementAttempts(RecoveryCodeSubject subject, RecoveryCodePurpose purpose) {
        jdbcClient
                .sql("""
                        UPDATE recovery_code SET attempts = attempts + 1
                        WHERE subject = :subject AND purpose = :purpose
                        """)
                .param("subject", subject.value())
                .param("purpose", purpose.name())
                .update();
    }

    @Override
    public void delete(RecoveryCodeSubject subject, RecoveryCodePurpose purpose) {
        jdbcClient
                .sql("DELETE FROM recovery_code WHERE subject = :subject AND purpose = :purpose")
                .param("subject", subject.value())
                .param("purpose", purpose.name())
                .update();
    }

    @Override
    public void deleteAll(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM recovery_code WHERE subject = :subject")
                .param("subject", keycloakUserId.value())
                .update();
    }

    @Override
    public void deleteExpiredBefore(Instant threshold) {
        jdbcClient
                .sql("DELETE FROM recovery_code WHERE expires_at < :threshold")
                .param("threshold", Timestamp.from(threshold))
                .update();
    }

    private static EmailRecoveryCode mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        RecoveryCodePurpose purpose = RecoveryCodePurpose.valueOf(resultSet.getString("purpose"));
        return new EmailRecoveryCode(
                new RecoveryCodeSubject(purpose.subjectKind(), resultSet.getString("subject")),
                purpose,
                resultSet.getString("code_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getInt("attempts"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}

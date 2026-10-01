package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves {@code V25__recovery_code_subject.sql} only renames the key column: a one-time code that
 * was stored under the old {@code keycloak_user_id} column before the migration is still there,
 * unchanged, under {@code subject} afterwards.
 */
@Testcontainers
class RecoveryCodeSubjectMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @Test
    void migratingToTheSubjectColumn_keepsTheExistingRecoveryCodeRows() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("24").load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");
        jdbcClient
                .sql("""
                        INSERT INTO recovery_code (keycloak_user_id, purpose, code_hash, expires_at, attempts, created_at)
                        VALUES ('account-before-rename', 'ATTACH_CONFIRM', 'hash-before-rename', :expiresAt, 2, :createdAt)
                        """)
                .param("expiresAt", Timestamp.from(createdAt.plusSeconds(900)))
                .param("createdAt", Timestamp.from(createdAt))
                .update();

        Flyway.configure().dataSource(dataSource).target("25").load().migrate();

        List<String> rowsAfterTheRename = jdbcClient
                .sql("SELECT subject || '|' || purpose || '|' || code_hash || '|' || attempts FROM recovery_code")
                .query(String.class)
                .list();
        assertThat(rowsAfterTheRename).containsExactly("account-before-rename|ATTACH_CONFIRM|hash-before-rename|2");
    }
}

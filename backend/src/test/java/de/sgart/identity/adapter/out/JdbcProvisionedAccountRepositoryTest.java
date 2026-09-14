package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccount;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers integration test against real PostgreSQL (mirrors {@link
 * JdbcMemberMappingRepositoryTest}) — the adapter/schema pair proving the durable account-lifecycle
 * shell store (Story 7.1, {@code V18__provisioned_account.sql}). Owns its own container lifecycle.
 */
@Testcontainers
class JdbcProvisionedAccountRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcProvisionedAccountRepository repository;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE provisioned_account").update();
        repository = new JdbcProvisionedAccountRepository(JdbcClient.create(dataSource));
    }

    @Test
    void recordIfAbsent_writesARowThatFindProvisionedBeforeReadsBack() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        Instant provisionedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);

        repository.recordIfAbsent(keycloakUserId, provisionedAt);

        assertThat(repository.findProvisionedBefore(Instant.now()))
                .containsExactly(new ProvisionedAccount(keycloakUserId, provisionedAt));
    }

    @Test
    void recordIfAbsent_calledTwiceForTheSamePerson_keepsTheFirstTimestampAndWritesOnlyOneRow() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        Instant firstAttempt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        Instant retryAfterADroppedResponse = Instant.now().truncatedTo(ChronoUnit.MICROS);

        repository.recordIfAbsent(keycloakUserId, firstAttempt);
        repository.recordIfAbsent(keycloakUserId, retryAfterADroppedResponse);

        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(1)))
                .containsExactly(new ProvisionedAccount(keycloakUserId, firstAttempt));
    }

    @Test
    void findProvisionedBefore_excludesRowsAtOrAfterTheThreshold() {
        Instant threshold = Instant.now();
        repository.recordIfAbsent(new KeycloakUserId("old-sub"), threshold.minusSeconds(10));
        repository.recordIfAbsent(new KeycloakUserId("new-sub"), threshold.plusSeconds(10));

        assertThat(repository.findProvisionedBefore(threshold))
                .extracting(ProvisionedAccount::keycloakUserId)
                .containsExactly(new KeycloakUserId("old-sub"));
    }

    @Test
    void delete_removesTheRow() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        repository.recordIfAbsent(keycloakUserId, Instant.now().minusSeconds(1));

        repository.delete(keycloakUserId);

        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(60))).isEmpty();
    }

    @Test
    void delete_isIdempotent() {
        repository.delete(new KeycloakUserId("never-existed"));

        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(60))).isEmpty();
    }
}

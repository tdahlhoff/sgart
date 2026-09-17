package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.AccountConsent;
import de.sgart.identity.domain.KeycloakUserId;
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
 * JdbcProvisionedAccountRepositoryTest}) — the adapter/schema pair proving the durable consent
 * record (Story 7.4, {@code V20__account_consent.sql}). Owns its own container lifecycle.
 */
@Testcontainers
class JdbcAccountConsentRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcAccountConsentRepository repository;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE account_consent").update();
        repository = new JdbcAccountConsentRepository(JdbcClient.create(dataSource));
    }

    @Test
    void record_writesARowThatFindForReadsBack() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        Instant acceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        repository.record(keycloakUserId, "2026-beta-1", acceptedAt);

        assertThat(repository.findFor(keycloakUserId))
                .contains(new AccountConsent(keycloakUserId, "2026-beta-1", acceptedAt));
    }

    @Test
    void record_reAcceptWithANewerVersion_overwritesTheRowRatherThanAddingASecond() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        Instant firstAcceptedAt = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        Instant secondAcceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        repository.record(keycloakUserId, "2026-beta-1", firstAcceptedAt);
        repository.record(keycloakUserId, "2026-beta-2", secondAcceptedAt);

        assertThat(repository.findFor(keycloakUserId))
                .contains(new AccountConsent(keycloakUserId, "2026-beta-2", secondAcceptedAt));
    }

    @Test
    void findFor_returnsEmptyWhenNoRowExists() {
        assertThat(repository.findFor(new KeycloakUserId("never-consented-sub"))).isEmpty();
    }

    @Test
    void accountConsentRow_isDeletedByDeleteFor() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        repository.record(keycloakUserId, "2026-beta-1", Instant.now().truncatedTo(ChronoUnit.MICROS));

        repository.deleteFor(keycloakUserId);

        assertThat(repository.findFor(keycloakUserId)).isEmpty();
    }

    @Test
    void deleteFor_isIdempotent() {
        repository.deleteFor(new KeycloakUserId("never-existed"));

        assertThat(repository.findFor(new KeycloakUserId("never-existed"))).isEmpty();
    }
}

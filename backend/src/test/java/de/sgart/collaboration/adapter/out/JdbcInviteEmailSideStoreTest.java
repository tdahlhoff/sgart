package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.shared.InviteId;
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
 * Testcontainers integration test against real PostgreSQL (Story 4.1, T10) — the adapter/schema
 * pair for the AD-6 raw-email side-store, the only place a raw invite email is persisted. Owns its
 * own container lifecycle; never points at the dev compose Postgres. Mirrors {@code
 * JdbcMemberMappingRepositoryTest}.
 */
@Testcontainers
class JdbcInviteEmailSideStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcInviteEmailSideStore sideStore;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE invite_email_side_store").update();
        sideStore = new JdbcInviteEmailSideStore(JdbcClient.create(dataSource));
    }

    @Test
    void store_writesARowThatFindEmailReadsBack() {
        InviteId inviteId = InviteId.generate();
        NormalizedEmail email = NormalizedEmail.fromRaw("anna@example.com");

        sideStore.store(inviteId, email);

        assertThat(sideStore.findEmail(inviteId)).contains(email);
    }

    @Test
    void findEmail_isEmptyForAnUnknownInvite() {
        assertThat(sideStore.findEmail(InviteId.generate())).isEmpty();
    }

    @Test
    void purge_removesTheRow() {
        InviteId inviteId = InviteId.generate();
        sideStore.store(inviteId, NormalizedEmail.fromRaw("anna@example.com"));

        sideStore.purge(inviteId);

        assertThat(sideStore.findEmail(inviteId)).isEmpty();
    }
}

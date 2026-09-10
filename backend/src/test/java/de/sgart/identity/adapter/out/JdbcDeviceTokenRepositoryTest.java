package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
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
 * Testcontainers integration test against real PostgreSQL (Story 4.5, AC5) — the adapter/schema
 * pair (V17) proving the durable device-token store. Mirrors {@link JdbcMemberMappingRepositoryTest}.
 */
@Testcontainers
class JdbcDeviceTokenRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcDeviceTokenRepository repository;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE device_token").update();
        repository = new JdbcDeviceTokenRepository(JdbcClient.create(dataSource));
    }

    private static Instant registeredAt() {
        // Truncate to microseconds — PostgreSQL TIMESTAMPTZ round-trips at microsecond precision,
        // finer than that would make the read-back assertion brittle.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void upsert_writesARowThatFindByTokenReadsBack() {
        Instant registeredAt = registeredAt();
        DeviceToken deviceToken = new DeviceToken(new KeycloakUserId("anna-sub"), "token-1", DevicePlatform.ANDROID, registeredAt);

        repository.upsert(deviceToken);

        assertThat(repository.findByToken("token-1")).contains(deviceToken);
    }

    @Test
    void upsert_reRegisteringTheSameTokenRefreshesTheRowInsteadOfDuplicatingIt() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        repository.upsert(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.ANDROID, registeredAt()));
        Instant refreshedAt = registeredAt().plusSeconds(60);

        repository.upsert(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.IOS, refreshedAt));

        assertThat(repository.findByKeycloakUserId(keycloakUserId)).hasSize(1);
        assertThat(repository.findByToken("token-1"))
                .contains(new DeviceToken(keycloakUserId, "token-1", DevicePlatform.IOS, refreshedAt));
    }

    @Test
    void findByToken_isEmptyForAnUnknownToken() {
        assertThat(repository.findByToken("unknown")).isEmpty();
    }

    @Test
    void findByKeycloakUserId_returnsEveryDeviceForThatPersonOnly() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        KeycloakUserId bob = new KeycloakUserId("bob-sub");
        Instant registeredAt = registeredAt();
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, registeredAt));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, registeredAt));
        repository.upsert(new DeviceToken(bob, "bob-phone", DevicePlatform.ANDROID, registeredAt));

        assertThat(repository.findByKeycloakUserId(anna))
                .extracting(DeviceToken::token)
                .containsExactlyInAnyOrder("anna-phone", "anna-tablet");
    }

    @Test
    void deleteByToken_removesOnlyThatTokenAndIsIdempotent() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        Instant registeredAt = registeredAt();
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, registeredAt));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, registeredAt));

        repository.deleteByToken("anna-phone");
        repository.deleteByToken("anna-phone"); // idempotent

        assertThat(repository.findByToken("anna-phone")).isEmpty();
        assertThat(repository.findByToken("anna-tablet")).isPresent();
    }

    /** The Epic-6 erasure hook (AD-7) — removes every device for a person, none of another's. */
    @Test
    void deleteAllForUser_removesEveryDeviceForThatPersonAndNoneOfAnothers() {
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        KeycloakUserId bob = new KeycloakUserId("bob-sub");
        Instant registeredAt = registeredAt();
        repository.upsert(new DeviceToken(anna, "anna-phone", DevicePlatform.ANDROID, registeredAt));
        repository.upsert(new DeviceToken(anna, "anna-tablet", DevicePlatform.IOS, registeredAt));
        repository.upsert(new DeviceToken(bob, "bob-phone", DevicePlatform.ANDROID, registeredAt));

        repository.deleteAllForUser(anna);

        assertThat(repository.findByKeycloakUserId(anna)).isEmpty();
        assertThat(repository.findByKeycloakUserId(bob)).hasSize(1);
    }

    @Test
    void deleteAllForUser_isIdempotent() {
        repository.deleteAllForUser(new KeycloakUserId("stranger-sub"));

        assertThat(repository.findByKeycloakUserId(new KeycloakUserId("stranger-sub"))).isEmpty();
    }
}

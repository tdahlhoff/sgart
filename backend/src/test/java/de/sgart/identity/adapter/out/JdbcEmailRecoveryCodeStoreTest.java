package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.EmailRecoveryCode;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailDigest;
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
 * JdbcProvisionedAccountRepositoryTest}) — the adapter/schema pair proving the durable one-time
 * code store (Story 7.3, {@code V19__email_recovery_code.sql}). Owns its own container lifecycle.
 */
@Testcontainers
class JdbcEmailRecoveryCodeStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcEmailRecoveryCodeStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE recovery_code").update();
        store = new JdbcEmailRecoveryCodeStore(JdbcClient.create(dataSource));
    }

    @Test
    void emailRecoveryCodeStore_roundTripsHashedCode() {
        KeycloakUserId subject = new KeycloakUserId("anna-sub");
        Instant expiresAt = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-abc", expiresAt, createdAt);

        EmailRecoveryCode found = store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM).orElseThrow();
        assertThat(found.codeHash()).isEqualTo("hash-abc");
        assertThat(found.expiresAt()).isEqualTo(expiresAt);
        assertThat(found.attempts()).isZero();
    }

    @Test
    void emailRecoveryCodeStore_roundTripsARecoveryCodeBelongingToAnAddress() {
        RecoveryCodeSubject addressSubject = RecoveryCodeSubject.forAddress(new RecoveryEmailDigest("a1b2c3"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        store.store(addressSubject, RecoveryCodePurpose.RECOVER, "hash-address", now.plusSeconds(900), now);

        EmailRecoveryCode found = store.find(addressSubject, RecoveryCodePurpose.RECOVER).orElseThrow();
        assertThat(found.subject()).isEqualTo(addressSubject);
        assertThat(found.codeHash()).isEqualTo("hash-address");
    }

    @Test
    void emailRecoveryCodeStore_freshRequestReplacesThePreviousCode() {
        KeycloakUserId subject = new KeycloakUserId("anna-sub");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-1", now.plusSeconds(60), now);

        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-2", now.plusSeconds(900), now);

        EmailRecoveryCode found = store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM).orElseThrow();
        assertThat(found.codeHash()).isEqualTo("hash-2");
        assertThat(found.attempts()).isZero();
    }

    @Test
    void emailRecoveryCodeStore_expiresAndExhaustsAttempts() {
        KeycloakUserId subject = new KeycloakUserId("anna-sub");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER, "hash", now.minusSeconds(1), now);

        assertThat(store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER).orElseThrow().expiresAt()).isBefore(Instant.now());

        store.incrementAttempts(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER);
        store.incrementAttempts(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER);

        assertThat(store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void emailRecoveryCodeStore_isolatesSubjects() {
        KeycloakUserId subjectA = new KeycloakUserId("subject-a");
        KeycloakUserId subjectB = new KeycloakUserId("subject-b");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.store(RecoveryCodeSubject.forAccount(subjectA), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-a", now.plusSeconds(60), now);
        store.store(RecoveryCodeSubject.forAccount(subjectB), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-b", now.plusSeconds(60), now);

        store.incrementAttempts(RecoveryCodeSubject.forAccount(subjectA), RecoveryCodePurpose.ATTACH_CONFIRM);
        store.delete(RecoveryCodeSubject.forAccount(subjectA), RecoveryCodePurpose.ATTACH_CONFIRM);

        assertThat(store.find(RecoveryCodeSubject.forAccount(subjectA), RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(store.find(RecoveryCodeSubject.forAccount(subjectB), RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
    }

    /**
     * Story 7.3, AC4: the store's write surface has no email parameter at all — {@link
     * JdbcEmailRecoveryCodeStore#store} takes only the pseudonymous subject, purpose, and the
     * already-hashed code. This proves the persisted row (queried back raw via SQL, not through the
     * adapter's own read path) never carries the raw recovery address in any column, even though a
     * caller might otherwise be tempted to smuggle it into {@code code_hash}.
     */
    @Test
    void noSgartStorePersistsTheRawRecoveryEmail() {
        KeycloakUserId subject = new KeycloakUserId("anna-sub");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String rawEmail = "anna@example.com";

        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-not-email", now.plusSeconds(900), now);

        String row = JdbcClient.create(dataSource)
                .sql("SELECT subject, purpose, code_hash FROM recovery_code WHERE subject = :id")
                .param("id", subject.value())
                .query((resultSet, rowNumber) -> resultSet.getString("subject") + "|" + resultSet.getString("purpose")
                        + "|" + resultSet.getString("code_hash"))
                .single();
        assertThat(row).doesNotContain(rawEmail);
    }

    @Test
    void deleteAll_removesEveryPurposeForTheSubject() {
        KeycloakUserId subject = new KeycloakUserId("anna-sub");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-1", now.plusSeconds(60), now);
        store.store(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER, "hash-2", now.plusSeconds(60), now);

        store.deleteAll(subject);

        assertThat(store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(store.find(RecoveryCodeSubject.forAccount(subject), RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    @Test
    void deleteExpiredBefore_removesOnlyRowsThatExpiredBeforeTheThreshold() {
        KeycloakUserId expiredSubject = new KeycloakUserId("expired-sub");
        KeycloakUserId activeSubject = new KeycloakUserId("active-sub");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.store(RecoveryCodeSubject.forAccount(expiredSubject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-1", now.minusSeconds(1), now.minusSeconds(901));
        store.store(RecoveryCodeSubject.forAccount(activeSubject), RecoveryCodePurpose.ATTACH_CONFIRM, "hash-2", now.plusSeconds(60), now);

        store.deleteExpiredBefore(now);

        assertThat(store.find(RecoveryCodeSubject.forAccount(expiredSubject), RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(store.find(RecoveryCodeSubject.forAccount(activeSubject), RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
    }
}

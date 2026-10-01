package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingExport;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers integration test against real PostgreSQL for the recovery-email binding index
 * ({@code V24__recovery_email_binding.sql}), including the privacy guarantees: erasure, export,
 * and retention.
 */
@Testcontainers
class JdbcRecoveryEmailBindingRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z").truncatedTo(ChronoUnit.MICROS);
    private static final RecoveryEmailDigest SHARED_DIGEST = new RecoveryEmailDigest("shared-digest");
    private static final RecoveryEmailDigest OTHER_DIGEST = new RecoveryEmailDigest("other-digest");
    private static final KeycloakUserId ACCOUNT_A = new KeycloakUserId("account-a");
    private static final KeycloakUserId ACCOUNT_B = new KeycloakUserId("account-b");
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("tester@example.test");

    private static DataSource dataSource;

    private JdbcRecoveryEmailBindingRepository repository;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE recovery_email_binding").update();
        repository = new JdbcRecoveryEmailBindingRepository(
                JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void savePending_theSameDigestOnTwoAccounts_isAllowed() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_B, HINT, NOW));

        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_B).orElseThrow().confirm(NOW));

        assertThat(repository.findConfirmedFor(SHARED_DIGEST))
                .extracting(RecoveryEmailBinding::keycloakUserId)
                .containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B);
    }

    @Test
    void findConfirmedFor_ignoresPendingBindings() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));

        assertThat(repository.findConfirmedFor(SHARED_DIGEST)).isEmpty();
        assertThat(repository.findConfirmedFor(ACCOUNT_A)).isEmpty();
        assertThat(repository.hasConfirmedBindingFor(ACCOUNT_A)).isFalse();
    }

    @Test
    void savePending_replacesTheAccountsEarlierPendingBinding() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));

        repository.savePending(RecoveryEmailBinding.pending(OTHER_DIGEST, ACCOUNT_A, HINT, NOW));

        assertThat(repository.findPendingFor(ACCOUNT_A).orElseThrow().digest()).isEqualTo(OTHER_DIGEST);
    }

    @Test
    void savePending_doesNotDowngradeAnAlreadyConfirmedBindingOfTheSameAddress() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));

        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW.plusSeconds(60)));

        assertThat(repository.hasConfirmedBindingFor(ACCOUNT_A)).isTrue();
    }

    @Test
    void confirm_replacesTheAccountsPreviousConfirmedBindingAndLeavesOtherAccountsAlone() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_B, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_B).orElseThrow().confirm(NOW));
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));

        repository.savePending(RecoveryEmailBinding.pending(OTHER_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));

        assertThat(repository.findConfirmedFor(ACCOUNT_A).orElseThrow().digest()).isEqualTo(OTHER_DIGEST);
        assertThat(repository.findConfirmedFor(SHARED_DIGEST))
                .extracting(RecoveryEmailBinding::keycloakUserId)
                .containsExactly(ACCOUNT_B);
    }

    @Test
    void deleteAllFor_removesEveryBindingOfThePersonAndNoOtherPerson() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));
        repository.savePending(RecoveryEmailBinding.pending(OTHER_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_B, HINT, NOW));

        repository.deleteAllFor(ACCOUNT_A);

        assertThat(repository.findAllFor(ACCOUNT_A)).isEmpty();
        assertThat(repository.findAllFor(ACCOUNT_B)).hasSize(1);
    }

    @Test
    void findAllFor_exportsHintAndConfirmationTimeButNoDigest() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW.plusSeconds(30)));

        List<RecoveryEmailBindingExport> exported = repository.findAllFor(ACCOUNT_A);

        assertThat(exported).containsExactly(new RecoveryEmailBindingExport(HINT, NOW.plusSeconds(30)));
        assertThat(RecoveryEmailBindingExport.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("hint", "confirmedAt");
    }

    @Test
    void deletePendingCreatedBefore_keepsConfirmedBindings() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW.minusSeconds(3600)));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW.minusSeconds(3500)));
        repository.savePending(RecoveryEmailBinding.pending(OTHER_DIGEST, ACCOUNT_B, HINT, NOW.minusSeconds(3600)));
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, new KeycloakUserId("account-c"), HINT, NOW));

        repository.deletePendingCreatedBefore(NOW.minusSeconds(900));

        assertThat(repository.hasConfirmedBindingFor(ACCOUNT_A)).isTrue();
        assertThat(repository.findPendingFor(ACCOUNT_B)).isEmpty();
        assertThat(repository.findPendingFor(new KeycloakUserId("account-c"))).isPresent();
    }

    @Test
    void confirm_aBindingThatWasNeverSaved_failsAndKeepsTheAccountsConfirmedBinding() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        repository.confirm(repository.findPendingFor(ACCOUNT_A).orElseThrow().confirm(NOW));
        RecoveryEmailBinding neverSaved = RecoveryEmailBinding.pending(OTHER_DIGEST, ACCOUNT_A, HINT, NOW).confirm(NOW);

        assertThatThrownBy(() -> repository.confirm(neverSaved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(OTHER_DIGEST.value())
                .hasMessageNotContaining(ACCOUNT_A.value());

        assertThat(repository.findConfirmedFor(ACCOUNT_A).orElseThrow().digest()).isEqualTo(SHARED_DIGEST);
    }

    @Test
    void savePending_whenTheInsertFails_keepsTheAccountsEarlierPendingBinding() {
        repository.savePending(RecoveryEmailBinding.pending(SHARED_DIGEST, ACCOUNT_A, HINT, NOW));
        RecoveryEmailDigest digestTooLongForTheColumn = new RecoveryEmailDigest("x".repeat(65));

        assertThatThrownBy(() -> repository.savePending(
                        RecoveryEmailBinding.pending(digestTooLongForTheColumn, ACCOUNT_A, HINT, NOW)))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.findPendingFor(ACCOUNT_A).orElseThrow().digest()).isEqualTo(SHARED_DIGEST);
    }
}

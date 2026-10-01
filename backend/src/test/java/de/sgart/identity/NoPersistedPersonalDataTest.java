package de.sgart.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;
import de.sgart.identity.domain.MemberMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * First-class privacy guarantee (AD-6): SGART never persists display name or email — they are
 * read live from Keycloak/JWT claims for display only (CLAUDE.md §5, "Right to erasure & data
 * portability" / "Storage limitation"). Synthetic data only, no real personal data anywhere in
 * this suite.
 */
class NoPersistedPersonalDataTest {

    @Test
    void theIdentityAclsSoleMapping_neverCarriesADisplayNameOrEmailField() {
        List<String> componentNames = Arrays.stream(MemberMapping.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertThat(componentNames).noneMatch(name -> name.contains("displayname") || name.contains("email"));
    }

    @Test
    void theLiveClaimsCallerType_neverReachesTheDomainOrAnOutboundAdapter() {
        JavaClasses identityClasses =
                new ClassFileImporter().withImportOption(new DoNotIncludeTests()).importPackages("de.sgart.identity");

        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage("de.sgart.identity.domain..", "de.sgart.identity.adapter.out..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("de.sgart.identity.adapter.in.security.AuthenticatedCaller")
                .as("AuthenticatedCaller (display name, read live for the /me response) must "
                        + "never reach the domain or an outbound/persistence adapter (AD-6)");

        rule.check(identityClasses);
    }

    /**
     * The documented exceptions to the guard below, named individually (not by pattern) so a
     * future migration cannot accidentally widen the exemption: {@code V13} created the mutable
     * {@code invite_email_side_store} table (Story 4.1, locked decision 3, AD-6) — the sole place a
     * raw invite email was ever persisted, purgeable by {@code invite_id}; {@code V21} retires it
     * (Story 7.5, AD-6) — the invite path collects no email at all now, and the {@code DROP TABLE}
     * statement necessarily still names the table it is dropping.
     */
    private static final String INVITE_EMAIL_SIDE_STORE_MIGRATION = "V13__invite_email_side_store.sql";

    private static final String INVITE_EMAIL_SIDE_STORE_DROP_MIGRATION = "V21__drop_invite_email_side_store.sql";

    /**
     * {@code V22} creates {@code membership_nickname} (Story 8.3, AD-6 rev F) — the one table allowed
     * to hold a person's self-chosen name; its own narrower guarantees are proven by {@link
     * #noPersistedPersonalData_membershipNicknameIsADocumentedAd6ExceptionForAFreelyChosenName}.
     */
    private static final String MEMBERSHIP_NICKNAME_MIGRATION = "V22__membership_nickname.sql";

    /**
     * {@code V24} creates {@code recovery_email_binding}: the table is named for what it is, so its
     * name contains "email". It holds only a keyed digest and a masked hint, never an address, which
     * {@link #recoveryEmailBindingTable_holdsOnlyADigestAndAMaskedHint} proves.
     */
    private static final String RECOVERY_EMAIL_BINDING_MIGRATION = "V24__recovery_email_binding.sql";

    /**
     * Extends the guarantee to the durable schema (Story 1.6): every Flyway migration — the
     * Identity ACL mapping table and the household read model alike — must never declare a
     * display-name/nickname/email column (AD-6, "Storage limitation" / "Right to erasure"), except
     * the individually named, documented AD-6 exceptions above.
     */
    @Test
    void noFlywayMigrationEverDeclaresADisplayNameNicknameOrEmailColumn() {
        Path migrationsDirectory = Path.of("src/main/resources/db/migration");
        List<String> forbiddenColumnNameFragments = List.of("display_name", "displayname", "nickname", "email");

        try (var migrationFiles = Files.list(migrationsDirectory)) {
            migrationFiles
                    .filter(path -> path.toString().endsWith(".sql"))
                    .filter(path -> !path.getFileName().toString().equals(INVITE_EMAIL_SIDE_STORE_MIGRATION))
                    .filter(path -> !path.getFileName().toString().equals(INVITE_EMAIL_SIDE_STORE_DROP_MIGRATION))
                    .filter(path -> !path.getFileName().toString().equals(MEMBERSHIP_NICKNAME_MIGRATION))
                    .filter(path -> !path.getFileName().toString().equals(RECOVERY_EMAIL_BINDING_MIGRATION))
                    .forEach(path -> {
                        String sql = withoutSqlComments(readFile(path)).toLowerCase(Locale.ROOT);
                        forbiddenColumnNameFragments.forEach(forbidden -> assertThat(sql)
                                .as("Flyway migration %s must not declare a %s column (AD-6)",
                                        path.getFileName(), forbidden)
                                .doesNotContain(forbidden));
                    });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Guard-still-works regression (Story 4.1): proves the whitelist above is narrow — a
     * <em>different</em> migration (the new {@code invite_read_model}, which must never carry an
     * email/HMAC column, AD-6/§5) still trips the guard if it ever declared one. Without this test,
     * the whitelist could silently widen (e.g. to a glob) without anything noticing.
     */
    @Test
    void theInviteReadModelMigrationStillTripsTheGuardIfItDeclaredAnEmailColumn() {
        Path inviteReadModelMigration = Path.of("src/main/resources/db/migration/V12__invite_read_model.sql");
        String sql = withoutSqlComments(readFile(inviteReadModelMigration)).toLowerCase(Locale.ROOT);

        assertThat(sql)
                .as("invite_read_model must carry no email/HMAC column (AD-6, §5) — the raw email lives "
                        + "only in the whitelisted invite_email_side_store")
                .doesNotContain("email");
    }

    /**
     * Story 7.3, AC4: the one-time-code table carries no email column. Its original definition
     * (V19) is keyed by the pseudonymous {@code keycloak_user_id}; V25 later renamed that key
     * column to a generic {@code subject}, because a recovery code belongs to an address digest.
     * Either way the table never holds a plaintext address.
     */
    @Test
    void emailRecoveryCodeTable_carriesNoEmailColumn() {
        Path migration = Path.of("src/main/resources/db/migration/V19__email_recovery_code.sql");
        String sql = withoutSqlComments(readFile(migration)).toLowerCase(Locale.ROOT);

        assertThat(sql).doesNotContain("email");
        assertThat(sql).contains("keycloak_user_id");
    }

    /**
     * Story 7.4, AC2/AC5: the consent record is keyed by the pseudonymous {@code
     * keycloak_user_id} alone and carries no email/name/IP column — only {@code notice_version} +
     * {@code accepted_at} (AD-6, data minimization).
     */
    @Test
    void noPersistedPersonalData_accountConsentHoldsNoPii() {
        Path migration = Path.of("src/main/resources/db/migration/V20__account_consent.sql");
        String sql = withoutSqlComments(readFile(migration)).toLowerCase(Locale.ROOT);

        assertThat(sql).doesNotContain("email");
        assertThat(sql).doesNotContain("display_name");
        assertThat(sql).doesNotContain("displayname");
        assertThat(sql).doesNotContain("ip_address");
        assertThat(sql).contains("keycloak_user_id");
    }

    /**
     * Story 8.3, AD-6 rev F — the <strong>documented, intentional</strong> exception to this
     * suite's "no persisted PII" guarantee: a self-chosen, per-household nickname is deliberately
     * persisted in {@code membership_nickname}. This is not a naming dodge — it is narrowly scoped
     * and proven here: (1) purpose — a freely-chosen, low-sensitivity in-household display name,
     * never a copy of the Keycloak {@code name}/{@code email} claim; (2) never in events — the
     * table lives only in {@code identity} adapters, never referenced by {@code
     * DomainEventJsonCodec} or any projector; (3) erasable — keyed by {@code keycloak_user_id} for
     * account erasure ({@link de.sgart.identity.domain.MembershipNicknameRepository#deleteFor})
     * and by {@code (keycloak_user_id, household_id)} for a membership de-link ({@code
     * deleteForMembership}), mirroring {@code identity_member_mapping}'s own erasure shape (AD-7).
     */
    @Test
    void noPersistedPersonalData_membershipNicknameIsADocumentedAd6ExceptionForAFreelyChosenName() {
        Path migration = Path.of("src/main/resources/db/migration/V22__membership_nickname.sql");
        String sql = withoutSqlComments(readFile(migration)).toLowerCase(Locale.ROOT);

        assertThat(sql)
                .as("membership_nickname is the one documented AD-6 exception (Story 8.3) — a "
                        + "self-chosen nickname, never Keycloak's own display name/email claim")
                .contains("nickname")
                .contains("keycloak_user_id")
                .doesNotContain("display_name")
                .doesNotContain("email");

        ArchRule neverReferencedFromEventCodec = noClasses()
                .that()
                .haveFullyQualifiedName("de.sgart.collaboration.adapter.out.DomainEventJsonCodec")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("de.sgart.identity.domain.MembershipNickname")
                .as("MembershipNickname (Story 8.3) must never reach the domain-event codec — it is "
                        + "never written into an event or event-derived projection (AD-5 untouched)");
        JavaClasses collaborationClasses = new ClassFileImporter()
                .withImportOption(new DoNotIncludeTests())
                .importPackages("de.sgart.collaboration", "de.sgart.identity");
        neverReferencedFromEventCodec.check(collaborationClasses);
    }

    /**
     * The recovery-email binding index stores no address: only {@code address_digest} (HMAC-SHA256
     * with a pepper) and {@code address_hint} (a masked display form), keyed by the pseudonymous
     * account id. The columns are read from the schema of a fully migrated PostgreSQL, so a later
     * {@code ALTER TABLE} or a column of any type is covered. Any new column must be a conscious
     * decision that updates this test.
     */
    @Test
    void recoveryEmailBindingTable_holdsOnlyADigestAndAMaskedHint() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();

            List<String> columnNames = JdbcClient.create(dataSource)
                    .sql("""
                            SELECT column_name FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'recovery_email_binding'
                            """)
                    .query(String.class)
                    .list();

            assertThat(columnNames)
                    .containsExactlyInAnyOrder(
                            "address_digest", "keycloak_user_id", "address_hint", "confirmed_at", "created_at");
            assertThat(columnNames).noneMatch(name -> name.contains("email") || name.equals("address"));
        }
    }

    /** Strips {@code -- ...} line comments so prose mentioning "email"/"display name" (like this
     * very migration's own explanatory header) never trips the column-name check below it. */
    private static String withoutSqlComments(String sql) {
        return sql.lines().map(line -> line.replaceFirst("--.*$", "")).reduce("", (a, b) -> a + "\n" + b);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

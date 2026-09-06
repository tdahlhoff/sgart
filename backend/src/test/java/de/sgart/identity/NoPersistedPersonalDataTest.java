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
import org.junit.jupiter.api.Test;

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
                .as("AuthenticatedCaller (display name/email, read live for the /me response) must "
                        + "never reach the domain or an outbound/persistence adapter (AD-6)");

        rule.check(identityClasses);
    }

    /**
     * The one documented exception to the guard below (Story 4.1, locked decision 3, AD-6): the
     * mutable {@code invite_email_side_store} table is the sole place a raw invite email is
     * persisted, purgeable by {@code invite_id}. Every other migration — including the new {@code
     * invite_read_model} — keeps tripping the guard on an email/display-name column; this file is
     * named, not exempted by pattern, so a future migration cannot accidentally widen the exemption.
     */
    private static final String INVITE_EMAIL_SIDE_STORE_MIGRATION = "V13__invite_email_side_store.sql";

    /**
     * Extends the guarantee to the durable schema (Story 1.6): every Flyway migration — the
     * Identity ACL mapping table and the household read model alike — must never declare a
     * display-name/email column (AD-6, "Storage limitation" / "Right to erasure"), except the one
     * documented AD-6 side-store exception (Story 4.1, locked decision 3).
     */
    @Test
    void noFlywayMigrationEverDeclaresADisplayNameOrEmailColumn() {
        Path migrationsDirectory = Path.of("src/main/resources/db/migration");
        List<String> forbiddenColumnNameFragments = List.of("display_name", "displayname", "email");

        try (var migrationFiles = Files.list(migrationsDirectory)) {
            migrationFiles
                    .filter(path -> path.toString().endsWith(".sql"))
                    .filter(path -> !path.getFileName().toString().equals(INVITE_EMAIL_SIDE_STORE_MIGRATION))
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

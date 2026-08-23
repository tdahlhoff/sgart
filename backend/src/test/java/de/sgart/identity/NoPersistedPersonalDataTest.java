package de.sgart.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;
import de.sgart.identity.domain.MemberMapping;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
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
}

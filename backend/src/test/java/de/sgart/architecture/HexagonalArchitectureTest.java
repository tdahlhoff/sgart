package de.sgart.architecture;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the architecture spine (AD-1, AD-2, AD-3) at compile time. These are permanent
 * guardrails, not a one-off check: a violation fails the build and blocks merge (NFR6).
 */
@AnalyzeClasses(packages = "de.sgart", importOptions = DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    /**
     * AD-1 — the domain is pure. No {@code ..domain..} class may reference a framework, persistence,
     * transport, identity-provider, or adapter type. Infrastructure reaches the domain only through
     * ports the domain itself owns.
     */
    @ArchTest
    static final ArchRule domainIsFreeOfInfrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "jakarta.servlet..",
                            "java.sql..",
                            "javax.sql..",
                            "org.keycloak..",
                            "io.kurrent..",
                            "com.eventstore..",
                            "..adapter..")
                    .as("the domain layer must not depend on any framework, persistence, "
                            + "transport, or adapter type (AD-1)");

    /**
     * AD-1 / hexagonal dependency direction: {@code adapter.in -> application -> domain}, and
     * {@code adapter.out} implements domain ports (driven side). Adapters are never accessed by an
     * inner layer. Dependencies on the shared kernel and external libraries are intentionally
     * ignored — only the relationships between these layers are constrained.
     */
    @ArchTest
    static final ArchRule layersRespectHexagonalDirection =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    // The scaffold's context packages are still empty; layers become populated as
                    // stories add code. The direction constraints below start enforcing then.
                    .withOptionalLayers(true)
                    .layer("Domain").definedBy("..domain..")
                    .layer("Application").definedBy("..application..")
                    .layer("AdapterIn").definedBy("..adapter.in..")
                    .layer("AdapterOut").definedBy("..adapter.out..")

                    .whereLayer("AdapterIn").mayNotBeAccessedByAnyLayer()
                    .whereLayer("AdapterOut").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application").mayOnlyBeAccessedByLayers("AdapterIn", "AdapterOut")
                    .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "AdapterOut");

    /**
     * AD-2 / AD-3 — bounded contexts are isolated. One context's domain never reaches into another
     * context's domain; contexts reference each other only by id and interact solely through
     * published application ports or async domain events.
     */
    @ArchTest
    static final ArchRule contextDomainsDoNotDependOnEachOther =
            slices().matching("de.sgart.(*).domain..")
                    .should().notDependOnEachOther()
                    .as("a context's domain must not depend on another context's domain (AD-2)");
}

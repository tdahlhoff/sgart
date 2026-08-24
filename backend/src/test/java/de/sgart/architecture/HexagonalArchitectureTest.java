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

    /**
     * AD-2 — a context reaches another context only through its published application ports, never
     * that context's internal domain. The Collaboration rename handler resolves the caller's
     * {@code MemberId} via Identity's {@code ResolveMemberIdentity} <em>application</em> port, and a
     * {@code String}-taking overload keeps {@code KeycloakUserId} inside Identity precisely so this
     * boundary holds. This rule turns that convention into a build-time guardrail: a regression that
     * imported {@code identity.domain} into {@code collaboration.application} would fail here.
     */
    @ArchTest
    static final ArchRule collaborationApplicationDoesNotReachIntoIdentityDomain =
            noClasses()
                    .that().resideInAPackage("de.sgart.collaboration.application..")
                    .should().dependOnClassesThat().resideInAPackage("de.sgart.identity.domain..")
                    .as("a context's application layer must reach another context only through its "
                            + "published application ports, never its domain (AD-2)");

    /**
     * AD-1 — the shared kernel is pure. The cross-context write-side envelope ({@code Command},
     * {@code DomainEvent}, the {@code EventStore} port, {@code EventSourcedAggregate}, the ids) must
     * never absorb a framework, persistence, event-store, or adapter type — so the real KurrentDB
     * client (Story 1.6) can implement the {@code EventStore} port in {@code adapter.out} while the
     * port itself stays infrastructure-free.
     */
    @ArchTest
    static final ArchRule sharedKernelIsFreeOfInfrastructure =
            noClasses()
                    .that().resideInAPackage("de.sgart.shared..")
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
                    .as("the shared kernel must not depend on any framework, persistence, "
                            + "event-store, or adapter type (AD-1)");
}

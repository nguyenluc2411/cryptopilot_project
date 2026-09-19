package com.cryptopilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the layer rules that apply inside every module. A module groups its classes into
 * {@code controller}, {@code service}, {@code repository}, {@code entity}, {@code dto},
 * {@code calculator}, {@code client}, {@code job}, {@code event} and {@code config}; each rule below
 * is the "must not depend on" half of one of those layers.
 *
 * <p>Cross-module access and cycles are a different concern and are checked by
 * {@link ModularityTest}. That also covers the {@code config} layer, whose only restriction is that
 * it must not reach another module's internals.
 *
 * <p>Every rule allows an empty match. The modules are built over later tasks, so a layer that has
 * no classes yet must pass rather than fail; the flag comes off a layer once it is populated.
 *
 * <p>Rule: SRS 4.2.5 (maintainability through separated layers and components).
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 4 (Layered
 * Architecture: each layer depends only downwards, and the domain depends on nothing technical).
 * <p>Reference: Humble, J. &amp; Farley, D. (2010). <i>Continuous Delivery</i>. Addison-Wesley, ch. 3
 * (a rule is only real once it fails the build).
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class LayerRulesTest {

    static final String ROOT = "com.cryptopilot";

    private static final String CONTROLLER = "com.cryptopilot..controller..";
    private static final String SERVICE = "com.cryptopilot..service..";
    private static final String REPOSITORY = "com.cryptopilot..repository..";
    private static final String ENTITY = "com.cryptopilot..entity..";
    private static final String DTO = "com.cryptopilot..dto..";
    private static final String CALCULATOR = "com.cryptopilot..calculator..";
    private static final String CLIENT = "com.cryptopilot..client..";
    private static final String JOB = "com.cryptopilot..job..";
    private static final String EVENT = "com.cryptopilot..event..";
    private static final String CONFIG = "com.cryptopilot..config..";

    private static final String SPRING = "org.springframework..";
    private static final String JPA = "jakarta.persistence..";

    /** A controller maps DTOs and delegates; it never reaches storage. */
    @ArchTest
    static final ArchRule controllers_doNotReachPersistence = noClasses()
            .that()
            .resideInAPackage(CONTROLLER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REPOSITORY, ENTITY)
            .allowEmptyShould(true);

    /** A service is called by the web layer, never the other way round. */
    @ArchTest
    static final ArchRule services_doNotDependOnControllers = noClasses()
            .that()
            .resideInAPackage(SERVICE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(CONTROLLER)
            .allowEmptyShould(true);

    /** A repository answers queries; the use case that needs one sits above it. */
    @ArchTest
    static final ArchRule repositories_doNotDependOnServicesOrControllers = noClasses()
            .that()
            .resideInAPackage(REPOSITORY)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, CONTROLLER)
            .allowEmptyShould(true);

    /** An entity carries its own invariants and knows nothing about the layers above it. */
    @ArchTest
    static final ArchRule entities_doNotDependOnUpperLayers = noClasses()
            .that()
            .resideInAPackage(ENTITY)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER)
            .allowEmptyShould(true);

    /** A DTO is a request or response shape, so it stays free of behaviour and storage. */
    @ArchTest
    static final ArchRule dtos_doNotDependOnServicesOrRepositories = noClasses()
            .that()
            .resideInAPackage(DTO)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, REPOSITORY)
            .allowEmptyShould(true);

    /**
     * The strictest rule of the set. A calculator holds a business formula and nothing else, so it
     * must stay pure Java: no framework, no persistence, no I/O. That is what lets every rule be
     * unit tested without a Spring context, a database or a clock.
     */
    @ArchTest
    static final ArchRule calculators_arePureJava = noClasses()
            .that()
            .resideInAPackage(CALCULATOR)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SPRING, JPA, REPOSITORY, SERVICE, CONTROLLER, CLIENT, JOB)
            .allowEmptyShould(true);

    /** A client adapts an external system; it does not read or write our own tables. */
    @ArchTest
    static final ArchRule clients_doNotReachPersistenceOrControllers = noClasses()
            .that()
            .resideInAPackage(CLIENT)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REPOSITORY, CONTROLLER)
            .allowEmptyShould(true);

    /** A job orchestrates: it triggers a service, which owns the transaction and the query. */
    @ArchTest
    static final ArchRule jobs_doNotReachRepositoriesDirectly = noClasses()
            .that()
            .resideInAPackage(JOB)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REPOSITORY)
            .allowEmptyShould(true);

    /**
     * An event is part of a module's public API, so it must be readable by another module without
     * dragging in anything internal.
     */
    @ArchTest
    static final ArchRule events_doNotDependOnModuleInternals = noClasses()
            .that()
            .resideInAPackage(EVENT)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(CONTROLLER, SERVICE, REPOSITORY, ENTITY, DTO, CALCULATOR, CLIENT, JOB, CONFIG)
            .allowEmptyShould(true);
}

package com.cryptopilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.stereotype.Service;

/**
 * Enforces the package convention of D-48 in every module: service interfaces in {@code service}, their
 * {@code @Service} implementations in {@code service.impl}, API records in {@code dto.request} and
 * {@code dto.response}, internal records in {@code model}, and the Binance client records kept inside
 * {@code market}.
 *
 * <p>The record rule is about files: a private record nested inside an implementation, such as the result of one
 * of its private steps, is part of that class and stays there.
 *
 * <p>Every rule allows an empty match, as in {@link LayerRulesTest}: a module built later passes until it has
 * classes of the kind a rule is about.
 *
 * <p>Rule: SRS 4.2.5 (maintainability through separated layers and components); D-48.
 * <p>Reference: Martin, R. C. (2017). <i>Clean Architecture</i>. Prentice Hall, ch. 11 (the Dependency
 * Inversion Principle: callers depend on abstractions, not on the concrete classes that implement them).
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class PackageConventionTest {

    private static final String SERVICE = "com.cryptopilot..service..";
    private static final String SERVICE_IMPL = "com.cryptopilot..service.impl..";
    private static final String CONTROLLER = "com.cryptopilot..controller..";
    private static final String DTO = "com.cryptopilot..dto..";
    private static final String MODEL = "com.cryptopilot..model..";
    private static final String MARKET = "com.cryptopilot.market..";
    private static final String MARKET_CLIENT = "com.cryptopilot.market.client..";

    private static final DescribedPredicate<JavaClass> RECORDS = DescribedPredicate.describe(
            "top-level records", javaClass -> javaClass.isTopLevelClass() && javaClass.isRecord());

    private static final DescribedPredicate<JavaClass> NAMED_LIKE_A_DTO = DescribedPredicate.describe(
            "named like a DTO (…Request, …Response)",
            javaClass -> javaClass.isTopLevelClass()
                            && javaClass.getSimpleName().endsWith("Request")
                    || javaClass.isTopLevelClass() && javaClass.getSimpleName().endsWith("Response"));

    /** A service package holds interfaces, implementations and helpers; data travels in {@code dto} or {@code model}. */
    @ArchTest
    static final ArchRule services_holdNoRecordsOrDtos = classes()
            .that(RECORDS.or(NAMED_LIKE_A_DTO))
            .should()
            .resideOutsideOfPackage(SERVICE)
            .allowEmptyShould(true);

    /**
     * A {@code @Service} is an implementation: it lives in {@code service.impl} and implements a service interface
     * of its module, or the port it adapts, declared in a {@code client} package or at a module root.
     */
    @ArchTest
    static final ArchRule services_areImplementationsOfAnInterface = classes()
            .that()
            .areAnnotatedWith(Service.class)
            .should()
            .resideInAPackage(SERVICE_IMPL)
            .andShould(implementAServiceInterfaceOrAPort())
            .allowEmptyShould(true);

    /** A controller depends on the service interface, never on the implementation. */
    @ArchTest
    static final ArchRule controllers_doNotDependOnImplementations = noClasses()
            .that()
            .resideInAPackage(CONTROLLER)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(SERVICE_IMPL)
            .allowEmptyShould(true);

    /** A DTO may name an entity enum, and nothing else of the entity package. */
    @ArchTest
    static final ArchRule dtos_useOnlyEntityEnums = noClasses()
            .that()
            .resideInAPackage(DTO)
            .should()
            .dependOnClassesThat(DescribedPredicate.describe(
                    "are entity classes other than enums",
                    target -> target.getPackageName().contains(".entity") && !target.isEnum()))
            .allowEmptyShould(true);

    /** An internal model never reaches up into the layers that produce or expose it. */
    @ArchTest
    static final ArchRule models_doNotDependOnServicesOrControllers = noClasses()
            .that()
            .resideInAPackage(MODEL)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SERVICE, CONTROLLER)
            .allowEmptyShould(true);

    /** The Binance client's records are the exchange's shapes; they stay inside the market module. */
    @ArchTest
    static final ArchRule marketClient_isUsedOnlyInsideMarket = noClasses()
            .that()
            .resideOutsideOfPackage(MARKET)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(MARKET_CLIENT)
            .allowEmptyShould(true);

    private static ArchCondition<JavaClass> implementAServiceInterfaceOrAPort() {
        return new ArchCondition<>("implement an interface of a service package, a client package or a module root") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean implementsOne =
                        item.getRawInterfaces().stream().anyMatch(PackageConventionTest::isServiceOrPort);
                String message = item.getName() + (implementsOne ? " implements " : " does not implement ")
                        + "a service interface or a port";
                events.add(new SimpleConditionEvent(item, implementsOne, message));
            }
        };
    }

    private static boolean isServiceOrPort(JavaClass anInterface) {
        String pkg = anInterface.getPackageName();
        return pkg.matches("com\\.cryptopilot\\.\\w+\\.service")
                || pkg.matches("com\\.cryptopilot\\.\\w+\\.client(\\..*)?")
                || pkg.matches("com\\.cryptopilot\\.\\w+");
    }
}

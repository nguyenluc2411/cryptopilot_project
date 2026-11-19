package com.cryptopilot.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.cryptopilot.market.config.FallbackPriceConfig;
import com.cryptopilot.market.job.FallbackPriceJob;
import com.cryptopilot.market.service.impl.DisplayPriceServiceImpl;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The display-only fallback prices stay display only (D-158, invariant I1): nothing that fills, triggers, liquidates,
 * charges or alerts can reach them. Only the service that holds them, its configuration and its poll know the fallback
 * client; only the screens' controller, the status and the poll read the service. The market's API to the other modules
 * and its price cache never do, so a fallback price can never become an exchange price.
 *
 * <p>Rule: BR-09; D-158.
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class FallbackPriceIsolationTest {

    private static final String FALLBACK_CLIENT = "com.cryptopilot.market.client.fallback..";
    private static final String DISPLAY_SERVICE = "com.cryptopilot.market.service.DisplayPriceService";
    private static final String DISPLAY_IMPL = "com.cryptopilot.market.service.impl.DisplayPriceServiceImpl";

    /** The modules that act on prices never depend on the fallback at all. */
    @ArchTest
    static final ArchRule theTradingModules_neverReachTheFallback = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.cryptopilot.paper..",
                    "com.cryptopilot.trading..",
                    "com.cryptopilot.watchlist..",
                    "com.cryptopilot.journal..",
                    "com.cryptopilot.ai..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(FALLBACK_CLIENT)
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(DISPLAY_SERVICE)
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(DISPLAY_IMPL);

    /** The fallback client is known only to the service that holds its prices, its configuration and its poll. */
    @ArchTest
    static final ArchRule theFallbackClient_isKnownOnlyToTheDisplay = classes()
            .that()
            .resideInAPackage(FALLBACK_CLIENT)
            .should()
            .onlyBeAccessed()
            .byClassesThat(resideInAPackage(FALLBACK_CLIENT)
                    .or(belongToAnyOf(
                            DisplayPriceServiceImpl.class, FallbackPriceConfig.class, FallbackPriceJob.class)));

    /** The display service is read only by the screens' controller, the status and the poll. */
    @ArchTest
    static final ArchRule theDisplayService_isReadOnlyByTheScreens = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.cryptopilot.market.controller.FallbackPriceController")
            .and()
            .doNotHaveFullyQualifiedName("com.cryptopilot.market.service.impl.MarketStatusServiceImpl")
            .and()
            .doNotHaveFullyQualifiedName("com.cryptopilot.market.job.FallbackPriceJob")
            .and()
            .doNotHaveFullyQualifiedName(DISPLAY_IMPL)
            .and()
            .doNotHaveFullyQualifiedName(DISPLAY_SERVICE)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(DISPLAY_SERVICE)
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(DISPLAY_IMPL);
}

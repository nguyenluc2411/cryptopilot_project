package com.cryptopilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The setup score depends on market data and a style preset only: no class that computes or carries it may reach the
 * user's profile, a risk profile or the trading module, so no risk profile can change a score.
 *
 * <p>Rule: BR-13; D-53 (rule 1).
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class SetupScoreIndependenceTest {

    private static final DescribedPredicate<JavaClass> SETUP_SCORE = DescribedPredicate.describe(
            "a setup score class",
            type -> type.getPackageName().startsWith("com.cryptopilot.market")
                    && (type.getSimpleName().startsWith("Setup")
                            || type.getSimpleName().startsWith("Component")
                            || type.getSimpleName().equals("StylePreset")
                            || type.getSimpleName().equals("DerivativesInputs")
                            || type.getSimpleName().equals("DominantSide")));

    private static final DescribedPredicate<JavaClass> PROFILE_OR_RISK = DescribedPredicate.describe(
            "a user, risk profile or trading style class",
            type -> type.getSimpleName().contains("Risk")
                    || type.getSimpleName().contains("TradingStyle")
                    || type.getSimpleName().contains("Profile"));

    @ArchTest
    static final ArchRule setupScore_doesNotReachUserAuthOrTrading = noClasses()
            .that(SETUP_SCORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.cryptopilot.user..", "com.cryptopilot.auth..", "com.cryptopilot.trading..");

    @ArchTest
    static final ArchRule setupScore_doesNotReachARiskProfile =
            noClasses().that(SETUP_SCORE).should().dependOnClassesThat(PROFILE_OR_RISK);
}

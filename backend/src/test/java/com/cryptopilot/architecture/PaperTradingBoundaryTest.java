package com.cryptopilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Paper trading and the plan module stay apart until their matching engines are one: neither depends on the other,
 * and only paper trading names the tables it owns. A trade order already names the plan it carries out (D-117), so
 * the link exists in the schema; the code that uses it is the merge of the engines, made in one change across both.
 *
 * <p>Rule: TR-02; D-117.
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class PaperTradingBoundaryTest {

    /** The tables of paper trading since V35. */
    private static final Pattern PAPER_TABLES =
            Pattern.compile("\\b(wallet_balance|wallet_ledger_entry|order_list|trade_order|order_fill|equity_snapshot"
                    + "|order_matching_watermark)\\b");

    private static final Path MAIN = Path.of("src", "main", "java", "com", "cryptopilot");

    @ArchTest
    static final ArchRule trading_doesNotReachPaper = noClasses()
            .that()
            .resideInAPackage("com.cryptopilot.trading..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.cryptopilot.paper..");

    @ArchTest
    static final ArchRule paper_doesNotReachTrading = noClasses()
            .that()
            .resideInAPackage("com.cryptopilot.paper..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.cryptopilot.trading..");

    @Test
    void onlyPaperTrading_namesItsTables() throws IOException {
        List<String> outside;
        try (Stream<Path> sources = Files.walk(MAIN)) {
            outside = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(MAIN.resolve("paper")))
                    .filter(PaperTradingBoundaryTest::namesAPaperTable)
                    .map(path -> MAIN.relativize(path).toString())
                    .toList();
        }
        assertThat(outside)
                .as("classes outside paper trading that name its tables")
                .isEmpty();
    }

    private static boolean namesAPaperTable(Path source) {
        try {
            return PAPER_TABLES
                    .matcher(Files.readString(source, StandardCharsets.UTF_8))
                    .find();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + source, failure);
        }
    }
}

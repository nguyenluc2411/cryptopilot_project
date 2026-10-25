package com.cryptopilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The journal module alone writes {@code trading_journal}: the plans' module records a fill through its port
 * {@code trading.TradeJournal}, which the journal implements, and never reaches the journal's entity, repository or
 * table itself. The dependency points from {@code journal} to {@code trading}, never back.
 *
 * <p>Three views of one rule, because each can be dodged on its own: the bytecode (types), the mapping (an entity on
 * the table) and the SQL text (a native write, which leaves no type behind).
 *
 * <p>Rule: BR-34; TECHNICAL_DESIGN section 2 (a module writes only its own tables); D-119.
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 1 and 13 (each module owns its
 * data; others reach it through the owner's API).
 */
@AnalyzeClasses(packages = LayerRulesTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class JournalOwnershipTest {

    private static final String JOURNAL = "com.cryptopilot.journal..";
    private static final String TRADING = "com.cryptopilot.trading..";
    private static final String JOURNAL_TABLE = "trading_journal";

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "cryptopilot");

    /** A write statement on the journal table, over any whitespace or line break of a text block, in any case. */
    private static final Pattern WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+" + JOURNAL_TABLE + "\\b",
            Pattern.CASE_INSENSITIVE);

    /** The plans' module records fills through its own port and knows nothing of the module that keeps them. */
    @ArchTest
    static final ArchRule trading_neverDependsOnTheJournal = noClasses()
            .that()
            .resideInAPackage(TRADING)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(JOURNAL);

    /** Only the journal reaches its entity and repository, so only it can write through them. */
    @ArchTest
    static final ArchRule onlyTheJournal_reachesItsEntityAndRepository = noClasses()
            .that()
            .resideOutsideOfPackage(JOURNAL)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.cryptopilot.journal.entity..", "com.cryptopilot.journal.repository..");

    /** No entity of another module is mapped onto the journal table. */
    @ArchTest
    static final ArchRule onlyTheJournal_mapsAnEntityOnItsTable =
            classes().that(mappedOnto(JOURNAL_TABLE)).should().resideInAPackage("com.cryptopilot.journal.entity..");

    /** No source outside the journal holds a native write on its table: not in trading, not anywhere else. */
    @Test
    void BR34_noModuleButTheJournal_writesTheJournalTableInSql() throws IOException {
        List<String> writers;
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            writers = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(SOURCE_ROOT.resolve("journal")))
                    .filter(JournalOwnershipTest::writesTheJournalTable)
                    .map(Path::toString)
                    .toList();
        }

        assertThat(writers)
                .as("only the journal module writes trading_journal (D-119)")
                .isEmpty();
    }

    /** The scan finds what it looks for, so an empty result above means something. */
    @Test
    void BR34_theScan_recognisesAWriteInEachForm() {
        assertThat(Stream.of(
                                "insert into trading_journal (journal_id)",
                                "UPDATE trading_journal SET trade_status = 'CLOSED'",
                                "delete\n   from   trading_journal where plan_id = ?",
                                "merge into trading_journal using")
                        .allMatch(sql -> WRITE.matcher(sql).find()))
                .isTrue();
        assertThat(WRITE.matcher("select exists (select 1 from trading_journal where pair_id = ?)")
                        .find())
                .isFalse();
        assertThat(WRITE.matcher("update trading_journal_archive set x = 1").find())
                .isFalse();
    }

    private static boolean writesTheJournalTable(Path source) {
        try {
            return WRITE.matcher(Files.readString(source)).find();
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot read " + source, failed);
        }
    }

    private static DescribedPredicate<JavaClass> mappedOnto(String table) {
        return DescribedPredicate.describe(
                "mapped onto " + table,
                javaClass -> javaClass.isAnnotatedWith(Table.class)
                        && table.equals(
                                javaClass.getAnnotationOfType(Table.class).name()));
    }
}

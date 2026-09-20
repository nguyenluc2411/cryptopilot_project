package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.util.FileCopyUtils;

/**
 * What the seed migration put into a freshly migrated database, read back out of the database
 * itself. Nothing here looks at the text of the migration: a test that reads the SQL it is testing
 * proves only that the file says what the file says.
 *
 * <p>Three kinds of assertion. The first is what is there — the rows, their keys and their values,
 * with every value that an approved rule fixes named against that rule. The second is what is not
 * there: every table the seed deliberately leaves alone is empty, which is how a pair list invented
 * to make a demo look complete, or a sample row that escaped the development-only set, is caught.
 * The third is that running the seed again changes nothing.
 *
 * <p>Rule: BR-05 (roles), BR-15, BR-17, BR-29, BR-36, BR-44, BR-54 (the seeded defaults); SRS
 * 3.1.5.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SeedDataTest {

    /**
     * The instant every seeded row carries. The seed must not call {@code now()}: two databases
     * seeded a month apart have to hold identical rows, and a test cannot pin a value the migration
     * reads off the wall clock.
     */
    private static final OffsetDateTime EPOCH = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** The fixed key of the bootstrap administrator, which the migration writes as a literal. */
    private static final String ADMIN_ID = "019b76da-a800-7000-8000-000000000001";

    /** The tables the seed writes, with the number of rows each one is supposed to receive. */
    private static final Map<String, Integer> SEEDED_ROW_COUNTS =
            Map.of("system_setting", 16, "trading_strategy", 3, "user_account", 1, "user_profile", 1);

    /**
     * Tables that hold no business rows but are legitimately non-empty after a migration: Flyway's
     * own history, and the event publication registry, which is a technical table (A-09).
     */
    private static final List<String> TECHNICAL_TABLES = List.of("flyway_schema_history", "event_publication");

    /** A foreign key as {@code pg_get_constraintdef} prints it. */
    private static final Pattern FOREIGN_KEY =
            Pattern.compile("FOREIGN KEY \\(([^)]+)\\) REFERENCES ([^(]+)\\(([^)]+)\\)");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    // ------------------------------------------------------------------------------------------
    // What the seed wrote
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} holds {1} seeded rows")
    @CsvSource({"system_setting, 16", "trading_strategy, 3", "user_account, 1", "user_profile, 1"})
    void seededTable_holdsExactlyTheExpectedNumberOfRows(String table, int expected) {
        assertThat(countOf(table)).isEqualTo(expected);
    }

    @Test
    void seededKeys_areTheFixedVersionSevenLiteralsTheMigrationNames() {
        List<String> strategyIds = jdbc.sql("select strategy_id::text from trading_strategy order by strategy_code")
                .query(String.class)
                .list();
        List<String> accountIds = jdbc.sql("select user_id::text from user_account")
                .query(String.class)
                .list();

        assertThat(strategyIds)
                .as("a generated key would differ between databases, so no test could name a seeded row")
                .containsExactly(
                        "019b76da-a800-7001-8000-000000000001",
                        "019b76da-a800-7001-8000-000000000003",
                        "019b76da-a800-7001-8000-000000000002");
        assertThat(accountIds).containsExactly(ADMIN_ID);
        for (String id : strategyIds) {
            assertVersionSevenLayout(id);
        }
        assertVersionSevenLayout(ADMIN_ID);
    }

    @Test
    void seededInstants_areTheFixedEpochAndNotTheTimeTheMigrationRan() {
        List<OffsetDateTime> instants = new ArrayList<>();
        instants.addAll(jdbc.sql("select updated_at from system_setting")
                .query(OffsetDateTime.class)
                .list());
        instants.addAll(
                jdbc.sql("select created_at from trading_strategy union all select updated_at from trading_strategy")
                        .query(OffsetDateTime.class)
                        .list());
        instants.addAll(jdbc.sql(
                        "select created_at from user_account union all select updated_at from user_account union all select email_verified_at from user_account")
                .query(OffsetDateTime.class)
                .list());
        instants.addAll(jdbc.sql("select created_at from user_profile union all select updated_at from user_profile")
                .query(OffsetDateTime.class)
                .list());

        assertThat(instants).isNotEmpty().allSatisfy(instant -> assertThat(instant.toInstant())
                .isEqualTo(EPOCH.toInstant()));
    }

    @Test
    void seededStrategies_areTheThreeCodesTheDataModelNames() {
        List<String> codes = jdbc.sql("select strategy_code from trading_strategy order by strategy_code")
                .query(String.class)
                .list();

        assertThat(codes).containsExactly("BREAKOUT", "SCALPING", "SWING");
    }

    /**
     * The bootstrap account exists so that the ADMIN role is reachable at all: BR-05 gives every
     * account exactly one role, makes every self-registered account a TRADER, and lets only an
     * Admin assign ADMIN. Without a seeded Admin the first one could never be created.
     *
     * <p>This proves the bootstrap half of the rule. That a self-registered account really comes
     * out as TRADER, and that only an Admin may change a role, are the other two halves and belong
     * to the registration and the user administration tasks.
     */
    @Test
    void BR05_theSeededAccount_carriesTheAdminRoleThatOnlyAnAdminCouldOtherwiseGrant() {
        Map<String, Object> admin = jdbc.sql("select email, role, account_status, email_verified_at from user_account")
                .query()
                .singleRow();

        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(admin.get("account_status")).isEqualTo("ACTIVE");
        assertThat(admin.get("email"))
                .as("the .invalid domain of RFC 2606 can never resolve, so the row cannot be a person's")
                .isEqualTo("admin@cryptopilot.invalid");
        assertThat(admin.get("email_verified_at"))
                .as("BR-01 could otherwise never be satisfied for an address that receives no mail")
                .isNotNull();
    }

    @Test
    void theSeededCredential_isWhateverTheEnvironmentSuppliedAndNothingThatWasCommitted() {
        String stored = jdbc.sql("select password_hash from user_account")
                .query(String.class)
                .single();

        assertThat(stored)
                .as("the hash reaches the database as a migration placeholder, never as a literal in the file")
                .isEqualTo(flyway.getConfiguration().getPlaceholders().get("admin_password_hash"));
    }

    /**
     * The direction the seed fails in when nobody supplies a hash: an account that exists and that
     * no password opens, rather than an account whose password is in a committed file.
     */
    @Test
    void theSeededCredential_isUnusableUntilAnEnvironmentSuppliesOne() {
        assumeThat(System.getenv("ADMIN_PASSWORD_HASH"))
                .as("this environment supplies a real hash, so there is no fallback to observe")
                .isNull();

        String stored = jdbc.sql("select password_hash from user_account")
                .query(String.class)
                .single();

        assertThat(stored).isEqualTo("!ADMIN_PASSWORD_HASH_NOT_SET");
        assertThat(stored)
                .as("not a hash in any format an encoder produces, so no password can match it")
                .doesNotStartWith("$");
    }

    // ------------------------------------------------------------------------------------------
    // The values approved rules fix
    // ------------------------------------------------------------------------------------------

    /** BR-15: the watchlist limit, with the default the rule states. */
    @Test
    void BR15_theWatchlistLimit_isSeededWithTheDefaultTheRuleStates() {
        assertThat(settingValue("MAX_WATCHLIST_ITEMS")).isEqualTo("50");
    }

    /** BR-17: the limit on alerts in status ACTIVE, with the default the rule states. */
    @Test
    void BR17_theActiveAlertLimit_isSeededWithTheDefaultTheRuleStates() {
        assertThat(settingValue("MAX_ACTIVE_ALERTS")).isEqualTo("20");
    }

    /**
     * BR-29 names five warning thresholds and says they are configurable in system settings. What
     * is proved here is that each one has the value the rule states, at the scale of the column it
     * will be compared against. That the risk calculation then raises the warning at that boundary
     * is the rule itself and belongs to the trading plan task.
     */
    @ParameterizedTest(name = "BR-29 {0} = {1}")
    @CsvSource({
        "WARN_LOW_RR_RATIO, 1.50000000",
        "WARN_OVERSIZED_POSITION_RISK_PERCENT, 2.000",
        "WARN_HIGH_LEVERAGE, 20",
        "WARN_WIDE_STOP_LOSS_PERCENT, 10.000",
        "WARN_HIGH_FUNDING_RATE, 0.00100000"
    })
    void BR29_warningThreshold_isSeededWithTheValueTheRuleStates(String key, String expected) {
        assertThat(settingValue(key)).isEqualTo(expected);
    }

    /**
     * BR-36 states the default simulated fee rates and calls them configurable: spot 0.1% for both
     * roles, futures 0.02% maker and 0.05% taker. Proved here as stored values; that a fill is
     * charged at the maker rate for a limit entry and at the taker rate otherwise belongs to the
     * matching engine task.
     */
    @ParameterizedTest(name = "BR-36 {0} = {1}")
    @CsvSource({
        "SPOT_MAKER_FEE, 0.00100000",
        "SPOT_TAKER_FEE, 0.00100000",
        "FUTURES_MAKER_FEE, 0.00020000",
        "FUTURES_TAKER_FEE, 0.00050000"
    })
    void BR36_simulatedFeeRate_isSeededWithTheDefaultTheRuleStates(String key, String expected) {
        assertThat(settingValue(key)).isEqualTo(expected);
    }

    /** BR-44: the media limits the rule calls configurable — four images of 5 MB, or one video of 100 MB and five minutes. */
    @ParameterizedTest(name = "BR-44 {0} = {1}")
    @CsvSource({
        "MAX_IMAGES_PER_POST, 4",
        "MAX_IMAGE_SIZE_MB, 5",
        "MAX_VIDEO_SIZE_MB, 100",
        "MAX_VIDEO_DURATION_SECONDS, 300"
    })
    void BR44_mediaLimit_isSeededWithTheValueTheRuleStates(String key, String expected) {
        assertThat(settingValue(key)).isEqualTo(expected);
    }

    /** BR-54: the window an unpaid subscription order survives, with the default the rule states. */
    @Test
    void BR54_theOrderExpiryWindow_isSeededWithTheDefaultTheRuleStates() {
        assertThat(settingValue("ORDER_EXPIRY_MINUTES")).isEqualTo("15");
    }

    @Test
    void everySeededSetting_carriesAValueItsDeclaredTypeParses() {
        List<Map<String, Object>> settings = jdbc.sql(
                        "select setting_key, setting_value, value_type from system_setting")
                .query()
                .listOfRows();

        assertThat(settings).hasSize(16).allSatisfy(setting -> {
            String key = (String) setting.get("setting_key");
            String value = (String) setting.get("setting_value");
            switch ((String) setting.get("value_type")) {
                case "INT" ->
                    assertThatCode(() -> Integer.parseInt(value))
                            .as("%s is declared INT", key)
                            .doesNotThrowAnyException();
                case "DECIMAL" ->
                    assertThatCode(() -> new BigDecimal(value))
                            .as("%s is declared DECIMAL", key)
                            .doesNotThrowAnyException();
                case "BOOLEAN" ->
                    assertThat(value).as("%s is declared BOOLEAN", key).isIn("true", "false");
                default -> assertThat(value).as("%s is declared STRING", key).isNotBlank();
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // What the seed deliberately left alone
    // ------------------------------------------------------------------------------------------

    /**
     * The production migration path writes rows into four tables and no others. This is the
     * assertion a sample row has to get past: a pair invented to make a demo browsable, or a demo
     * account that reached the versioned migrations instead of the development-only set, shows up
     * here as a table that is no longer empty.
     */
    @Test
    void everyTableTheSeedDoesNotWrite_isEmptyAfterTheProductionMigrations() {
        List<String> tables = jdbc.sql("""
                        select table_name
                          from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                         order by table_name""").query(String.class).list();

        List<String> nonEmpty = new ArrayList<>();
        for (String table : tables) {
            if (SEEDED_ROW_COUNTS.containsKey(table) || TECHNICAL_TABLES.contains(table)) {
                continue;
            }
            if (countOf(table) > 0) {
                nonEmpty.add(table);
            }
        }

        assertThat(nonEmpty)
                .as("market data is ingested, user content is created, and demo rows belong to the demo set")
                .isEmpty();
    }

    @Test
    void theProductionMigrationLocations_doNotIncludeTheDemoSet() {
        List<String> locations = Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString)
                .toList();

        assertThat(locations)
                .as("production cannot skip a demo row it is never offered")
                .noneMatch(location -> location.contains("demo"));
    }

    @Test
    void noSeededPairOrPackage_wasInventedWhileTheDecisionIsOpen() {
        assertThat(countOf("crypto_pair"))
                .as("the tick and step sizes a position size is rounded to (BR-23, BR-30) are not guessed")
                .isZero();
        assertThat(countOf("coin")).isZero();
        assertThat(countOf("subscription_package"))
                .as(
                        "an order copies the package price of the moment (BR-56), so a placeholder price would outlive itself")
                .isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Integrity
    // ------------------------------------------------------------------------------------------

    @Test
    void theSeededProfile_belongsToTheSeededAccount() {
        Integer joined = jdbc.sql("""
                        select count(*)
                          from user_profile p
                          join user_account a on a.user_id = p.user_id""").query(Integer.class).single();

        assertThat(joined).isEqualTo(1);
    }

    /**
     * No row anywhere points at a parent that is not there. The foreign keys are enforced on
     * insert, so this passes trivially while they are all in place — which is the point: it is the
     * assertion that fails first if a seed is ever written in an order the schema does not allow,
     * or against a constraint somebody removed to make an insert fit.
     */
    @Test
    void noRow_pointsAtAParentThatDoesNotExist() {
        List<Map<String, Object>> foreignKeys = jdbc.sql("""
                        select rel.relname as child, pg_get_constraintdef(c.oid) as definition
                          from pg_constraint c
                          join pg_class rel on rel.oid = c.conrelid
                          join pg_namespace n on n.oid = rel.relnamespace
                         where c.contype = 'f' and n.nspname = 'public'
                         order by rel.relname, c.conname""").query().listOfRows();

        List<String> orphaned = new ArrayList<>();
        for (Map<String, Object> foreignKey : foreignKeys) {
            String child = (String) foreignKey.get("child");
            Matcher matcher = FOREIGN_KEY.matcher((String) foreignKey.get("definition"));
            assertThat(matcher.find()).as("unreadable foreign key on %s", child).isTrue();
            String[] childColumns = matcher.group(1).split(",\\s*");
            String parent = matcher.group(2).trim();
            String[] parentColumns = matcher.group(3).split(",\\s*");

            StringBuilder join = new StringBuilder();
            StringBuilder notNull = new StringBuilder();
            for (int i = 0; i < childColumns.length; i++) {
                join.append(i == 0 ? "" : " and ")
                        .append("p.")
                        .append(parentColumns[i])
                        .append(" = c.")
                        .append(childColumns[i]);
                notNull.append(i == 0 ? "" : " and ")
                        .append("c.")
                        .append(childColumns[i])
                        .append(" is not null");
            }
            Integer count = jdbc.sql("select count(*) from %s c where %s and not exists (select 1 from %s p where %s)"
                            .formatted(child, notNull, parent, join))
                    .query(Integer.class)
                    .single();
            if (count > 0) {
                orphaned.add(child + " -> " + parent);
            }
        }

        assertThat(foreignKeys).isNotEmpty();
        assertThat(orphaned).isEmpty();
    }

    /**
     * Every seeded value of an enumerated column is one its check constraint permits. The database
     * refuses anything else on insert, so a value outside the list cannot survive the migration —
     * this states the other direction: that the values chosen for the seed were read off the
     * constraints rather than off memory, and that a constraint later widened or narrowed still
     * covers them.
     */
    @Test
    void everySeededEnumeratedValue_isOneItsCheckConstraintPermits() {
        assertAllowed("user_account", "role", "role");
        assertAllowed("user_account", "account_status", "account_status");
        assertAllowed("system_setting", "value_type", "value_type");
    }

    // ------------------------------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------------------------------

    /**
     * The repeat execution path, run for real: the seed migration is read from the classpath, its
     * placeholders are resolved from the configuration Flyway itself used, and it is applied a
     * second time to the migrated database. Every seeded row is compared before and after.
     *
     * <p>Flyway will not re-run a versioned migration, so this is not what happens on a restart. It
     * is what happens when the file is applied by hand to a database that already has it — during a
     * recovery, or on an environment somebody is repairing — and it is the only way to show that
     * the conflict targets actually cover the rows the statements insert.
     */
    @Test
    void reapplyingTheSeed_changesNothing() throws Exception {
        List<Map<String, Object>> before = seededRows();

        applyScript("db/migration/V3__seed_reference_data.sql");

        assertThat(seededRows()).isEqualTo(before);
        SEEDED_ROW_COUNTS.forEach(
                (table, expected) -> assertThat(countOf(table)).as(table).isEqualTo(expected));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private void assertVersionSevenLayout(String uuid) {
        assertThat(uuid.charAt(14)).as("version nibble of %s", uuid).isEqualTo('7');
        assertThat(uuid.charAt(19)).as("variant nibble of %s", uuid).isIn('8', '9', 'a', 'b');
    }

    private void assertAllowed(String table, String column, String constraintFragment) {
        String definition = jdbc.sql("""
                        select pg_get_constraintdef(c.oid)
                          from pg_constraint c
                          join pg_class rel on rel.oid = c.conrelid
                          join pg_namespace n on n.oid = rel.relnamespace
                         where c.contype = 'c' and n.nspname = 'public' and rel.relname = ?
                           and pg_get_constraintdef(c.oid) like ?""")
                .param(table)
                .param("%" + constraintFragment + "%")
                .query(String.class)
                .single();
        List<String> seeded = jdbc.sql("select distinct %s from %s".formatted(column, table))
                .query(String.class)
                .list();

        assertThat(seeded).isNotEmpty().allSatisfy(value -> assertThat(definition)
                .as("%s.%s = %s", table, column, value)
                .contains("'" + value + "'"));
    }

    private String settingValue(String key) {
        return jdbc.sql("select setting_value from system_setting where setting_key = ?")
                .param(key)
                .query(String.class)
                .single();
    }

    private int countOf(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    /** Every seeded row of every seeded table, ordered so that two readings are comparable. */
    private List<Map<String, Object>> seededRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("select * from system_setting order by setting_key")
                .query()
                .listOfRows());
        rows.addAll(jdbc.sql("select * from trading_strategy order by strategy_code")
                .query()
                .listOfRows());
        rows.addAll(
                jdbc.sql("select * from user_account order by email").query().listOfRows());
        rows.addAll(jdbc.sql("select * from user_profile order by display_name")
                .query()
                .listOfRows());
        return rows;
    }

    private void applyScript(String classpathLocation) throws Exception {
        String sql = new String(
                FileCopyUtils.copyToByteArray(new ClassPathResource(classpathLocation).getInputStream()),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> placeholder :
                flyway.getConfiguration().getPlaceholders().entrySet()) {
            sql = sql.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        execute(sql, classpathLocation);
    }

    private void execute(String sql, String description) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), description),
                            StandardCharsets.UTF_8));
        }
    }
}

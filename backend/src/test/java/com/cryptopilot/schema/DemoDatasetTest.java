package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.dto.request.LeverageBracketRequest;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.dto.request.UpdatePairRequest;
import com.cryptopilot.market.dto.response.AdminPairResponse;
import com.cryptopilot.market.dto.response.LeverageBracketsResponse;
import com.cryptopilot.market.model.enums.PairStatus;
import com.cryptopilot.market.service.PairAdminService;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileCopyUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The development-only dataset, applied the way a developer's database applies it: the production
 * migrations first, then the demo location on top of them.
 *
 * <p>It runs in a database of its own on the same container. Applying it to the database the rest
 * of the suite shares would put demo rows in front of every other test, and what those tests exist
 * to assert is precisely that no demo row is there — so the one test that needs the demo set has to
 * keep it to itself. That is also what a real environment looks like: the demo location is a
 * property of one database, not of the image.
 *
 * <p>It also checks the data file of the WF-01 loader ({@code deploy/demo/wf01.json}, DEMO-01): the demo pairs and
 * their leverage brackets, and that the administrator's own service accepts those brackets. The loader writing them
 * into a running backend is {@link DemoLoaderTest}.
 *
 * <p>Rule: SRS 3.1.5; D-18 (the same database image in every environment); DEMO-01: BR-26, BR-27, BR-62, BR-66, UC-46.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DemoDatasetTest {

    private static final String DEMO_DATABASE = "cryptopilot_demo";

    /** The fixed key of the demo trader. Only the demo location writes it. */
    private static final String DEMO_TRADER_ID = "019b76da-a800-7d00-8000-000000000001";

    /** The bootstrap administrator of V3, the author of the bracket changes. */
    private static final UUID ADMIN_ID = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    /** A day of the capstone defence, inside the WF-01 subscriptions. */
    private static final Instant DEFENCE_DAY = Instant.parse("2027-03-15T03:00:00Z");

    /** The end of the WF-01 subscriptions, the first instant they no longer cover. */
    private static final Instant WF01_SUBSCRIPTIONS_END = Instant.parse("2027-10-25T00:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    @Autowired
    private PairAdminService pairAdmin;

    @Autowired
    private JdbcClient sharedJdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private SingleConnectionDataSource demoDataSource;
    private JdbcClient demoJdbc;

    /**
     * A database of its own, migrated through both locations. It is dropped and recreated for each
     * test, so a test never sees what another one left behind.
     */
    @BeforeEach
    void migrateDemoDatabase() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DEMO_DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DEMO_DATABASE);
        }

        String demoUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DEMO_DATABASE;
        Flyway.configure()
                .dataSource(demoUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .load()
                .migrate();

        demoDataSource =
                new SingleConnectionDataSource(demoUrl, container.getUsername(), container.getPassword(), true);
        demoJdbc = JdbcClient.create(demoDataSource);
    }

    @AfterEach
    void releaseDemoDatabase() {
        if (demoDataSource != null) {
            demoDataSource.destroy();
            demoDataSource = null;
            demoJdbc = null;
        }
    }

    @Test
    void theDemoSet_appliesOnTopOfTheProductionPathAndAddsOnlyItsOwnRows() {
        assertThat(count("user_account"))
                .as("the bootstrap administrator of the production path, the demo trader, one Trader per plan tier"
                        + " (T-108) and the three WF-01 accounts (DEMO-01)")
                .isEqualTo(8);
        assertThat(demoJdbc.sql("select email, role from user_account where user_id = ?::uuid")
                        .param(DEMO_TRADER_ID)
                        .query()
                        .singleRow())
                .containsEntry("email", "demo.trader@cryptopilot.invalid")
                .containsEntry("role", "TRADER");

        assertThat(count("user_profile")).isEqualTo(8);
        assertThat(count("subscription_order"))
                .as("the orders of the PRO and PREMIUM demo Traders, of T-108 and of WF-01")
                .isEqualTo(4);
        assertThat(count("user_subscription")).isEqualTo(4);
        assertThat(count("system_setting"))
                .as("the demo set adds no setting: the reference values are the same in every environment"
                        + " (V3's sixteen, less the two plan limits V10 and the two thresholds V13 removes,"
                        + " plus the clearance fee V26 adds, the four plan validity settings V32 adds, the two"
                        + " resend limits V33 adds and the fee share threshold V34 adds)")
                .isEqualTo(20);
        assertThat(count("trading_strategy")).isEqualTo(3);
        assertThat(count("crypto_pair"))
                .as("pairs come from the symbol synchronisation (NSF-01); deploy/demo/load-wf01.ps1 enables the demo"
                        + " pairs through the administrator's API once they exist")
                .isZero();
        assertThat(count("subscription_package"))
                .as("the packages are production data (V11), the same in every environment")
                .isEqualTo(5);
        assertThat(demoJdbc.sql("select source_name from news_source where is_active order by source_name")
                        .query(String.class)
                        .list())
                .as("the outlets the trial crawl read, development only; production starts with none")
                .containsExactly("Bitcoin.com Market Updates", "CoinDesk Markets", "Cointelegraph Markets");
    }

    @Test
    void reapplyingTheDemoSet_changesNothing() throws Exception {
        List<Map<String, Object>> before = accountRows();
        List<Map<String, Object>> sourcesBefore = newsSourceRows();

        applyDemoScript("db/demo/R__demo_dataset.sql");
        applyDemoScript("db/demo/R__demo_plan_accounts.sql");
        applyDemoScript("db/demo/R__demo_wf01_accounts.sql");
        applyDemoScript("db/demo/R__demo_news_sources.sql");

        assertThat(accountRows()).isEqualTo(before);
        assertThat(newsSourceRows()).hasSize(3).isEqualTo(sourcesBefore);
        assertThat(count("user_account")).isEqualTo(8);
        assertThat(count("user_profile")).isEqualTo(8);
        assertThat(count("subscription_order")).isEqualTo(4);
        assertThat(count("user_subscription")).isEqualTo(4);
    }

    /**
     * The WF-01 accounts (DEMO-01): verified Traders with the password of the environment, the profile each scenario
     * needs (BR-66) and, on a day of the defence, the tier of their subscription (BR-62); FREE has none, and the paid
     * ones are back on FREE once their year is over.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "demo.free@cryptopilot.invalid,    FREE,    CONSERVATIVE, 10000, 1,",
        "demo.pro@cryptopilot.invalid,     PRO,     BALANCED,     10000, 1, DAY",
        "demo.premium@cryptopilot.invalid, PREMIUM, AGGRESSIVE,   50000, 1,"
    })
    void BR62_BR66_eachWf01Account_hasItsTierAndProfile(
            String email, String tier, String riskProfile, BigDecimal capital, BigDecimal risk, String style) {
        Map<String, Object> account = demoJdbc.sql("""
                        select a.role, a.account_status, a.email_verified_at is not null as verified, a.password_hash,
                               p.default_capital, p.default_risk_percent, p.risk_profile, p.trading_style
                          from user_account a join user_profile p on p.user_id = a.user_id
                         where a.email = ?""").param(email).query().singleRow();

        assertThat(account)
                .containsEntry("role", "TRADER")
                .containsEntry("account_status", "ACTIVE")
                .containsEntry("verified", true)
                .containsEntry(
                        "password_hash",
                        configuredFlyway.getConfiguration().getPlaceholders().get("demo_password_hash"))
                .containsEntry("risk_profile", riskProfile)
                .containsEntry("trading_style", style);
        assertThat((BigDecimal) account.get("default_capital")).isEqualByComparingTo(capital);
        assertThat((BigDecimal) account.get("default_risk_percent")).isEqualByComparingTo(risk);
        assertThat(tierOn(email, DEFENCE_DAY)).isEqualTo(tier);
        assertThat(tierOn(email, WF01_SUBSCRIPTIONS_END)).isEqualTo("FREE");
    }

    /**
     * The pairs and brackets of the loader's data file: BTCUSDT and ETHUSDT, and Binance's brackets in the shape T-085
     * accepts — contiguous from 0, MMR strictly between 0 and 1, leverage never rising, the maintenance amount
     * continuous at every floor (BR-27) and no leverage above the pair's ceiling.
     */
    @Test
    void BR26_BR27_theDataFile_namesTheDemoPairsAndWellFormedBrackets() {
        DemoWf01Data data = DemoWf01Data.read();

        assertThat(data.symbols()).containsExactly("BTCUSDT", "ETHUSDT");
        for (String symbol : data.symbols()) {
            List<LeverageBracketRequest> brackets = data.brackets(symbol);
            assertThat(brackets).as(symbol).hasSize(12);
            assertThat(brackets.getFirst().notionalFloor()).isZero();
            assertThat(brackets.getFirst().maxLeverage())
                    .as("Binance's first tier allows 150x; the system's ceiling is 125x")
                    .isEqualTo(125)
                    .isEqualTo(data.maxLeverage(symbol));
            for (int index = 0; index < brackets.size(); index++) {
                LeverageBracketRequest row = brackets.get(index);
                assertThat(row.maintenanceMarginRate()).isStrictlyBetween(BigDecimal.ZERO, BigDecimal.ONE);
                assertThat(row.notionalCap()).isGreaterThan(row.notionalFloor());
                if (index > 0) {
                    LeverageBracketRequest previous = brackets.get(index - 1);
                    assertThat(row.notionalFloor()).isEqualByComparingTo(previous.notionalCap());
                    assertThat(row.maxLeverage()).isLessThanOrEqualTo(previous.maxLeverage());
                    assertThat(row.maintenanceAmount())
                            .as("%s bracket %d: cum(n) = cum(n-1) + floor(n) x (MMR(n) - MMR(n-1))", symbol, index + 1)
                            .isEqualByComparingTo(previous.maintenanceAmount()
                                    .add(row.notionalFloor()
                                            .multiply(row.maintenanceMarginRate()
                                                    .subtract(previous.maintenanceMarginRate()))));
                }
            }
        }
    }

    /**
     * The brackets of the data file go through the administrator's own service (UC-46), which stores them unchanged,
     * and the pair then takes its ceiling on both markets (UC-45). In a transaction that is rolled back, so the shared
     * database keeps no pair.
     */
    @Test
    void UC46_theDataFileBrackets_passTheAdministratorsValidation() {
        DemoWf01Data data = DemoWf01Data.read();

        inRolledBackTransaction(() -> {
            for (String symbol : data.symbols()) {
                UUID pair = anyFuturesPair();

                LeverageBracketsResponse stored =
                        pairAdmin.replaceBrackets(ADMIN_ID, pair, new LeverageBracketsRequest(data.brackets(symbol)));
                AdminPairResponse enabled = pairAdmin.update(
                        ADMIN_ID,
                        pair,
                        new UpdatePairRequest(PairStatus.ACTIVE, true, true, data.maxLeverage(symbol), 1));

                List<LeverageBracketRequest> wanted = data.brackets(symbol);
                assertThat(stored.brackets()).hasSize(wanted.size());
                for (int index = 0; index < wanted.size(); index++) {
                    LeverageTier tier = stored.brackets().get(index);
                    LeverageBracketRequest row = wanted.get(index);
                    assertThat(tier.notionalFloor()).isEqualByComparingTo(row.notionalFloor());
                    assertThat(tier.notionalCap()).isEqualByComparingTo(row.notionalCap());
                    assertThat(tier.maxLeverage()).isEqualTo(row.maxLeverage());
                    assertThat(tier.maintenanceMarginRate()).isEqualByComparingTo(row.maintenanceMarginRate());
                    assertThat(tier.maintenanceAmount()).isEqualByComparingTo(row.maintenanceAmount());
                }
                assertThat(enabled.status()).isEqualTo(PairStatus.ACTIVE);
                assertThat(enabled.spotEnabled()).isTrue();
                assertThat(enabled.futuresEnabled()).isTrue();
                assertThat(enabled.maxLeverage()).isEqualTo(125);
            }
        });
    }

    /** The other side: the same service refuses the file's brackets once one maintenance amount is off by one. */
    @Test
    void BR27_theDataFileBracketsWithOneMaintenanceAmountOff_areRejected() {
        List<LeverageBracketRequest> brackets =
                new ArrayList<>(DemoWf01Data.read().brackets("BTCUSDT"));
        LeverageBracketRequest second = brackets.get(1);
        brackets.set(
                1,
                new LeverageBracketRequest(
                        second.notionalFloor(),
                        second.notionalCap(),
                        second.maxLeverage(),
                        second.maintenanceMarginRate(),
                        second.maintenanceAmount().add(BigDecimal.ONE)));

        inRolledBackTransaction(() -> {
            UUID pair = anyFuturesPair();

            assertThatThrownBy(() -> pairAdmin.replaceBrackets(ADMIN_ID, pair, new LeverageBracketsRequest(brackets)))
                    .isInstanceOf(FieldValidationException.class)
                    .hasMessageContaining("brackets[1].maintenanceAmount");
        });
    }

    /**
     * A pair the exchange trades on both markets with its filters, as NSF-01 leaves it, switched off, under a symbol no
     * other test uses.
     */
    private UUID anyFuturesPair() {
        UUID pair = new MarketTestData(sharedJdbc, Instant.now())
                .pair("WF" + System.nanoTime() % 1_000_000_000L + "USDT", false, false, "TRADING", "TRADING", 0);
        sharedJdbc.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.00001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 100
                         where pair_id = ?""").param(pair).update();
        return pair;
    }

    /** The tier of the account's ACTIVE subscription covering this instant; FREE without one (BR-62). */
    private String tierOn(String email, Instant at) {
        return demoJdbc.sql("""
                        select coalesce((
                            select p.tier
                              from user_account a
                              join subscription_order o on o.user_id = a.user_id
                              join user_subscription s on s.order_id = o.order_id
                              join subscription_package p on p.package_id = o.package_id
                             where a.email = :email and s.subscription_status = 'ACTIVE'
                               and s.start_at <= :at and s.end_at > :at), 'FREE')""")
                .param("email", email)
                .param("at", Timestamp.from(at))
                .query(String.class)
                .single();
    }

    private void inRolledBackTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }

    private List<Map<String, Object>> accountRows() {
        List<Map<String, Object>> rows = new ArrayList<>(demoJdbc.sql("select * from user_account order by email")
                .query()
                .listOfRows());
        rows.addAll(demoJdbc.sql("select * from user_profile order by display_name")
                .query()
                .listOfRows());
        rows.addAll(demoJdbc.sql("select * from subscription_order order by order_code")
                .query()
                .listOfRows());
        rows.addAll(demoJdbc.sql("select * from user_subscription order by subscription_id")
                .query()
                .listOfRows());
        return rows;
    }

    private List<Map<String, Object>> newsSourceRows() {
        return demoJdbc.sql("select * from news_source order by source_id")
                .query()
                .listOfRows();
    }

    /** A demo file itself, with its placeholders resolved the way Flyway resolved them. */
    private void applyDemoScript(String location) throws Exception {
        String sql = new String(
                FileCopyUtils.copyToByteArray(new ClassPathResource(location).getInputStream()),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> placeholder :
                configuredFlyway.getConfiguration().getPlaceholders().entrySet()) {
            sql = sql.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        try (Connection connection = demoDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), location),
                            StandardCharsets.UTF_8));
        }
    }

    private int count(String table) {
        return demoJdbc.sql("select count(*) from " + table)
                .query(Integer.class)
                .single();
    }
}

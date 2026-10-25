package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V32 run over plans written at V31: an ACTIVE LIMIT, a DRAFT MARKET, an EXECUTED LIMIT and an EXECUTED MARKET, owned
 * by Traders with and without a trading style. Every check of V32 holds on them, the planned entry, the liquidity role
 * and the analysis timeframe are backfilled, and the ACTIVE plan keeps everything the matching engine reads.
 *
 * <p>Runs in a database of its own on the shared container, migrated to V31 first, so the rows exist before V32 sees
 * them.
 *
 * <p>Rule: BR-36, BR-68, BR-69, BR-70; D-111, D-112.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PlanValidityMigrationTest {

    private static final String DATABASE = "cryptopilot_v32";

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T08:00:00Z");
    private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-10-08T08:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String url;
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private UUID pair;

    @BeforeEach
    void migrateToV31() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
        flyway("31").migrate();

        dataSource = new SingleConnectionDataSource(url, container.getUsername(), container.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
        pair = insertPair();
    }

    @AfterEach
    void release() {
        if (dataSource != null) {
            dataSource.destroy();
            dataSource = null;
        }
    }

    @Test
    void BR69_BR36_existingPlans_takeTheirPlannedEntryAndLiquidityRole() {
        UUID trader = insertTrader("SWING");
        UUID activeLimit = insertPlan(trader, "LIMIT", "100", "ACTIVE");
        UUID draftMarket = insertPlan(trader, "MARKET", "101", "DRAFT");
        UUID executedLimit = insertPlan(trader, "LIMIT", "99", "EXECUTED");
        UUID executedMarket = insertPlan(trader, "MARKET", "102.5", "EXECUTED");

        flyway("latest").migrate();

        Map<String, Object> active = row(activeLimit);
        assertThat((BigDecimal) active.get("planned_entry_price")).isEqualByComparingTo("100");
        assertThat(active.get("fill_liquidity")).isNull();
        assertThat((BigDecimal) row(draftMarket).get("planned_entry_price"))
                .as("the reference price of its last save")
                .isEqualByComparingTo("101");
        assertThat(row(draftMarket).get("fill_liquidity")).isNull();
        assertThat((BigDecimal) row(executedLimit).get("planned_entry_price")).isEqualByComparingTo("99");
        assertThat(row(executedLimit).get("fill_liquidity")).isEqualTo("MAKER");
        assertThat(row(executedMarket).get("planned_entry_price"))
                .as("its entry price is the fill, so the planned entry is unknown")
                .isNull();
        assertThat(row(executedMarket).get("fill_liquidity")).isEqualTo("TAKER");
        for (UUID plan : new UUID[] {activeLimit, draftMarket, executedLimit, executedMarket}) {
            Map<String, Object> migrated = row(plan);
            assertThat(migrated.get("invalidated_at")).isNull();
            assertThat(migrated.get("invalidation_reason")).isNull();
            assertThat(migrated.get("invalidation_price")).isNull();
            assertThat(migrated.get("max_entry_deviation_r")).isNull();
            assertThat(migrated.get("setup_score_at_plan")).isNull();
            assertThat(migrated.get("setup_score_preset")).isNull();
        }
    }

    /** D-112: SCALPING 15m, DAY 1h, SWING and POSITION 4h; no style and no profile at all, 1h. */
    @ParameterizedTest(name = "style {0} -> {1}")
    @CsvSource({"SCALPING, 15m", "DAY, 1h", "SWING, 4h", "POSITION, 4h", "NONE, 1h", "NO_PROFILE, 1h"})
    void D112_existingPlans_takeTheTimeframeOfTheirOwnersPreset(String style, String expected) {
        UUID trader = insertTrader(style);
        UUID plan = insertPlan(trader, "LIMIT", "100", "ACTIVE");

        flyway("latest").migrate();

        assertThat(row(plan).get("analysis_timeframe")).isEqualTo(expected);
    }

    @Test
    void NSF07_anActivePlan_keepsWhatTheMatchingEngineReads_andIsStillSelectedForTheBooks() {
        UUID trader = insertTrader("DAY");
        UUID plan = insertPlan(trader, "LIMIT", "100", "ACTIVE");
        Map<String, Object> before = row(plan);

        flyway("latest").migrate();

        Map<String, Object> after = row(plan);
        for (String column : new String[] {
            "plan_status",
            "entry_type",
            "entry_price",
            "direction",
            "market_type",
            "activated_at",
            "expires_at",
            "stop_loss_price",
            "take_profit_price",
            "version"
        }) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        assertThat(activeLimitEntries()).extracting(TrackedEntry::planId).containsExactly(plan);
    }

    @Test
    void V32_everyCheck_holdsOnTheMigratedRows_andTheSettingsAreSeeded() {
        UUID scalper = insertTrader("SCALPING");
        UUID none = insertTrader("NONE");
        insertPlan(scalper, "LIMIT", "100", "ACTIVE");
        insertPlan(none, "MARKET", "101", "DRAFT");
        insertPlan(scalper, "LIMIT", "99", "EXECUTED");
        insertPlan(none, "MARKET", "102.5", "EXECUTED");

        flyway("latest").migrate();

        assertThat(jdbc.sql("""
                        select count(*) from pg_constraint
                         where conrelid = 'trading_plan'::regclass and contype = 'c' and not convalidated""").query(Integer.class).single())
                .as("every check validated against the existing rows")
                .isZero();
        assertThat(jdbc.sql("select count(*) from trading_plan where analysis_timeframe is null")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("""
                        select setting_key from system_setting
                         where setting_key in ('ENTRY_DEVIATION_CONFIRM_R', 'ENTRY_DEVIATION_BLOCK_R',
                                               'SETUP_SCORE_DROP_WARN', 'SLIPPAGE_BPS')""").query(String.class).list()).hasSize(4);
    }

    /**
     * What the matching books load, read by {@link TradingPlanRepository#findActiveWaitingEntries()} itself: the
     * repository is built over a session on this test's database instead of the application's.
     */
    private List<TrackedEntry> activeLimitEntries() {
        SessionFactory sessions = entityManagerFactory.unwrap(SessionFactory.class);
        try (Session session =
                sessions.withOptions().connection(dataSource.getConnection()).openSession()) {
            return new JpaRepositoryFactory(session)
                    .getRepository(TradingPlanRepository.class)
                    .findActiveWaitingEntries();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .target(target)
                .load();
    }

    /** A Trader; {@code NONE} has a profile without a style, {@code NO_PROFILE} no profile row. */
    private UUID insertTrader(String style) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@v30.invalid", AT, AT).update();
        if (!"NO_PROFILE".equals(style)) {
            jdbc.sql("""
                            insert into user_profile (user_id, display_name, trading_style, created_at, updated_at)
                            values (?, 'v30', ?, ?, ?)""")
                    .params(id, "NONE".equals(style) ? null : style, AT, AT)
                    .update();
        }
        return id;
    }

    private UUID insertPair() {
        UUID base = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{base, "PVB"}, {quote, "PVQ"}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], AT, AT)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'PVBPVQ', 'INACTIVE', ?, ?)""").params(id, base, quote, AT, AT).update();
        return id;
    }

    private UUID insertPlan(UUID owner, String entryType, String entryPrice, String status) {
        UUID id = UUID.randomUUID();
        boolean executed = "EXECUTED".equals(status);
        boolean activated = executed || "ACTIVE".equals(status);
        jdbc.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, activated_at, expires_at, executed_at,
                                                  fill_price, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', ?, ?, 95, 110, 1000, 1, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        owner,
                        pair,
                        entryType,
                        new BigDecimal(entryPrice),
                        status,
                        activated ? AT : null,
                        "LIMIT".equals(entryType) ? EXPIRY : null,
                        executed ? AT : null,
                        executed ? new BigDecimal(entryPrice) : null,
                        AT,
                        AT)
                .update();
        return id;
    }

    private Map<String, Object> row(UUID plan) {
        return jdbc.sql("select * from trading_plan where plan_id = ?")
                .param(plan)
                .query()
                .singleRow();
    }
}

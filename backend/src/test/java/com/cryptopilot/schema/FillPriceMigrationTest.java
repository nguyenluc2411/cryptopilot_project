package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V18 run over rows written at V17: EXECUTED plans take their entry price as fill price, and an EXECUTED row
 * without an entry price stops the migration before any change, with the count and the fix.
 *
 * <p>Runs in a database of its own on the shared container, migrated to V17 first, so the seeded rows exist
 * before V18 sees them.
 *
 * <p>Rule: BR-33, BR-34; D-78.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FillPriceMigrationTest {

    private static final String DATABASE = "cryptopilot_v18";

    /** The administrator V3 seeded; any account will do as the plan's owner. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-01T08:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    private String url;
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private UUID pair;

    @BeforeEach
    void migrateToV17() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
        flyway("17").migrate();

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
    void BR34_executedPlansWithAnEntryPrice_takeItAsFillPrice() {
        UUID limit = insertPlan("LIMIT", "100", "EXECUTED");
        UUID market = insertPlan("MARKET", "101.5", "EXECUTED");
        UUID activeMarket = insertPlan("MARKET", null, "ACTIVE");

        flyway("latest").migrate();

        assertThat(fillPrice(limit)).isEqualByComparingTo("100");
        assertThat(fillPrice(market)).isEqualByComparingTo("101.5");
        assertThat(fillPrice(activeMarket)).isNull();
    }

    @Test
    void BR34_anExecutedPlanWithoutAnEntryPrice_stopsV18WithTheCountAndTheFix() {
        insertPlan("MARKET", null, "EXECUTED");
        insertPlan("LIMIT", "100", "EXECUTED");

        assertThatThrownBy(() -> flyway("latest").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V18 stopped: 1 EXECUTED trading_plan row(s) have a NULL entry_price")
                .hasMessageContaining("Set entry_price of those rows to the price each plan was filled at");
        assertThat(jdbc.sql("select count(*) from information_schema.columns"
                                + " where table_name = 'trading_plan' and column_name = 'fill_price'")
                        .query(Integer.class)
                        .single())
                .as("nothing of V18 is applied")
                .isZero();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .target(target)
                .load();
    }

    private UUID insertPair() {
        UUID base = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{base, "FPB"}, {quote, "FPQ"}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], AT, AT)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'FPBFPQ', 'INACTIVE', ?, ?)""").params(id, base, quote, AT, AT).update();
        return id;
    }

    private UUID insertPlan(String entryType, String entryPrice, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, activated_at, executed_at,
                                                  created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', ?, ?, 95, 110, 1000, 1, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        SEEDED_ACCOUNT,
                        pair,
                        entryType,
                        entryPrice == null ? null : new BigDecimal(entryPrice),
                        status,
                        AT,
                        "EXECUTED".equals(status) ? AT : null,
                        AT,
                        AT)
                .update();
        return id;
    }

    private BigDecimal fillPrice(UUID plan) {
        return jdbc.sql("select fill_price from trading_plan where plan_id = ?")
                .param(plan)
                .query(BigDecimal.class)
                .list()
                .getFirst();
    }
}

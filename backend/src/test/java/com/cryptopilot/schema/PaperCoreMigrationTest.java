package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V47 run over paper trading rows written at V46: two Traders with an account each, their grants, a transfer, an OCO
 * with its two orders, a market order with its fill, an equity snapshot and a watermark. Every row is carried over to
 * its Trader, the transfer survives as its two keyed entries, the grant points at the Trader, and the rows of the
 * folded tables are kept aside untouched. Data V47 does not expect stops it before anything changes.
 *
 * <p>Runs in a database of its own on the shared container, migrated to V46 first, so the rows exist before V47 sees
 * them.
 *
 * <p>Rule: TR-02, TR-04, TR-13; D-117.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PaperCoreMigrationTest {

    private static final String DATABASE = "cryptopilot_v35";

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-20T08:00:00Z");

    @Autowired
    private PostgreSQLContainer container;

    @Autowired
    private Flyway configuredFlyway;

    private String url;
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private UUID pair;
    private UUID usdt;
    private UUID btc;

    @BeforeEach
    void migrateToV46() throws SQLException {
        String adminUrl = container.getJdbcUrl();
        try (Connection connection =
                        DriverManager.getConnection(adminUrl, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
        flyway("46").migrate();

        dataSource = new SingleConnectionDataSource(url, container.getUsername(), container.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
        usdt = insertCoin("PCIUSDT");
        btc = insertCoin("PCIBTC");
        pair = UUID.randomUUID();
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'PCIBTCPCIUSDT', 'ACTIVE', ?, ?)""").params(pair, btc, usdt, AT, AT).update();
    }

    @AfterEach
    void release() {
        if (dataSource != null) {
            dataSource.destroy();
            dataSource = null;
        }
    }

    @Test
    void everyRow_isCarriedOverToItsTrader() {
        Trader first = trader("first");
        Trader second = trader("second");
        UUID transfer = transfer(first, "retry-1", "100");
        UUID list = insertOrderList(first);
        UUID stop = insertOrder(first, "list-stop", list);
        UUID limit = insertOrder(first, "list-limit", list);
        UUID market = insertOrder(second, "market-1", null);
        UUID fill = insertFill(second, market);
        insertSnapshot(first);
        jdbc.sql("""
                        insert into paper_matching_watermark (market_type, pair_id, last_candle_open_time, updated_at)
                        values ('SPOT', ?, ?, ?)""").params(pair, AT, AT).update();

        flyway("latest").migrate();

        assertThat(ownerOf("wallet_balance", "balance_id", first.usdtSpot())).isEqualTo(first.user());
        assertThat(count("select count(*) from wallet_balance where user_id = ?", second.user()))
                .isEqualTo(3);
        assertThat(ownerOf("order_list", "list_id", list)).isEqualTo(first.user());
        for (UUID order : List.of(stop, limit)) {
            assertThat(ownerOf("trade_order", "order_id", order)).isEqualTo(first.user());
        }
        assertThat(ownerOf("trade_order", "order_id", market)).isEqualTo(second.user());
        assertThat(jdbc.sql("select plan_id is null from trade_order where order_id = ?")
                        .param(market)
                        .query(Boolean.class)
                        .single())
                .as("an order placed on the trading screen carries out no plan")
                .isTrue();
        assertThat(ownerOf("order_fill", "fill_id", fill)).isEqualTo(second.user());
        assertThat(count("select count(*) from equity_snapshot where user_id = ?", first.user()))
                .isEqualTo(1);
        assertThat(count("select count(*) from order_matching_watermark where pair_id = ?", pair))
                .isEqualTo(1);

        List<Map<String, Object>> legs = jdbc.sql("""
                        select wallet_type, amount, ref_type, ref_id, client_transfer_id, user_id
                          from wallet_ledger_entry where entry_type = 'TRANSFER' order by amount""").query().listOfRows();
        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(leg -> {
            assertThat(leg.get("ref_type")).isEqualTo("TRANSFER");
            assertThat(leg.get("ref_id")).isEqualTo(transfer);
            assertThat(leg.get("client_transfer_id")).isEqualTo("retry-1");
            assertThat(leg.get("user_id")).isEqualTo(first.user());
        });
        assertThat(legs.getFirst().get("wallet_type")).isEqualTo("SPOT");
        assertThat((BigDecimal) legs.getFirst().get("amount")).isEqualByComparingTo("-100");

        assertThat(jdbc.sql("""
                        select count(*) from wallet_ledger_entry
                         where entry_type = 'INITIAL_GRANT' and (ref_type <> 'USER' or ref_id <> user_id)""").query(Long.class).single())
                .as("the grant points at its Trader")
                .isZero();
        assertThat(count("select count(*) from legacy_paper_account where user_id = ?", first.user()))
                .isEqualTo(1);
        assertThat(count("select count(*) from legacy_paper_transfer where transfer_id = ?", transfer))
                .isEqualTo(1);
    }

    @Test
    void theMigratedLedger_stillAddsUpToTheBalances() {
        Trader first = trader("first");
        transfer(first, "retry-1", "250");

        flyway("latest").migrate();

        assertThat(jdbc.sql("""
                        select count(*) from wallet_balance b
                         where b.free_amount + b.locked_amount <> (
                               select coalesce(sum(e.amount), 0) from wallet_ledger_entry e
                                where e.user_id = b.user_id and e.wallet_type = b.wallet_type
                                  and e.coin_id = b.coin_id)""").query(Long.class).single()).isZero();
    }

    @Test
    void theMigratedKeys_stillRefuseASecondGrantAndAReusedTransferKey() {
        Trader first = trader("first");
        transfer(first, "retry-1", "100");

        flyway("latest").migrate();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertEntry(first.user(), "INITIAL_GRANT", "SPOT", usdt, "USER", first.user(), null));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertEntry(
                        first.user(), "TRANSFER", "FUTURES", usdt, "TRANSFER", UUID.randomUUID(), "retry-1"));
    }

    @Test
    void aFuturesPaperRow_stopsTheMigration_andChangesNothing() {
        Trader first = trader("first");
        jdbc.sql("""
                        insert into paper_futures_setting (setting_id, account_id, pair_id, leverage, margin_mode,
                                                           created_at, updated_at)
                        values (?, ?, ?, 10, 'CROSS', ?, ?)""").params(UUID.randomUUID(), first.account(), pair, AT, AT).update();

        assertThatExceptionOfType(FlywayException.class)
                .isThrownBy(() -> flyway("latest").migrate())
                .withMessageContaining("Futures paper rows exist");

        assertUntouched();
    }

    @Test
    void aTransferWithoutItsCredit_stopsTheMigration_andChangesNothing() {
        Trader first = trader("first");
        UUID transfer = transfer(first, "retry-1", "100");
        jdbc.sql("delete from paper_ledger_entry where ref_id = ? and amount > 0")
                .param(transfer)
                .update();

        assertThatExceptionOfType(FlywayException.class)
                .isThrownBy(() -> flyway("latest").migrate())
                .withMessageContaining("transfers do not have exactly their debit and credit entry");

        assertUntouched();
    }

    private void assertUntouched() {
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where version = '47' and success")
                        .query(Long.class)
                        .single())
                .as("V47 is not recorded as applied")
                .isZero();
        assertThat(jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = 'public' and table_name in ('paper_account', 'paper_order', 'paper_transfer')""").query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = 'public' and table_name in ('wallet_balance', 'trade_order')""").query(Long.class).single()).isZero();
    }

    // ---------------------------------------------------------------- the rows before V47

    /** A Trader, an account and its grant: 1000 USDT and 1 BTC in Spot, 500 USDT in Futures. */
    private Trader trader(String name) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(user, name + user + "@v35.invalid", AT, AT).update();
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_account (account_id, user_id, position_mode, created_at, updated_at)
                        values (?, ?, 'ONE_WAY', ?, ?)""").params(account, user, AT, AT).update();
        UUID usdtSpot = grant(account, "SPOT", usdt, "1000");
        grant(account, "SPOT", btc, "1");
        grant(account, "FUTURES", usdt, "500");
        return new Trader(user, account, usdtSpot);
    }

    private UUID grant(UUID account, String wallet, UUID coin, String amount) {
        UUID balance = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_balance (balance_id, account_id, wallet_type, coin_id, free_amount,
                                                   locked_amount, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 0, ?, ?)""")
                .params(balance, account, wallet, coin, new BigDecimal(amount), AT, AT)
                .update();
        insertLegacyEntry(account, "INITIAL_GRANT", wallet, coin, amount, amount, "ACCOUNT", account);
        return balance;
    }

    /** A transfer out of Spot into Futures, its two entries, and the balances it moved. */
    private UUID transfer(Trader trader, String key, String amount) {
        UUID transfer = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_transfer (transfer_id, account_id, client_transfer_id, coin_id, from_wallet,
                                                    to_wallet, amount, created_at, updated_at)
                        values (?, ?, ?, ?, 'SPOT', 'FUTURES', ?, ?, ?)""")
                .params(transfer, trader.account(), key, usdt, new BigDecimal(amount), AT, AT)
                .update();
        BigDecimal moved = new BigDecimal(amount);
        jdbc.sql("""
                        update paper_balance set free_amount = free_amount - ?
                         where account_id = ? and wallet_type = 'SPOT' and coin_id = ?""").params(moved, trader.account(), usdt).update();
        jdbc.sql("""
                        update paper_balance set free_amount = free_amount + ?
                         where account_id = ? and wallet_type = 'FUTURES' and coin_id = ?""").params(moved, trader.account(), usdt).update();
        insertLegacyEntry(
                trader.account(), "TRANSFER", "SPOT", usdt, moved.negate().toPlainString(), "0", "TRANSFER", transfer);
        insertLegacyEntry(trader.account(), "TRANSFER", "FUTURES", usdt, amount, "0", "TRANSFER", transfer);
        return transfer;
    }

    private void insertLegacyEntry(
            UUID account,
            String type,
            String wallet,
            UUID coin,
            String amount,
            String after,
            String refType,
            UUID refId) {
        jdbc.sql("""
                        insert into paper_ledger_entry (entry_id, account_id, wallet_type, coin_id, entry_type, amount,
                                                        balance_after_amount, ref_type, ref_id, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        account,
                        wallet,
                        coin,
                        type,
                        new BigDecimal(amount),
                        new BigDecimal(after),
                        refType,
                        refId,
                        AT,
                        AT)
                .update();
    }

    private void insertEntry(
            UUID user, String type, String wallet, UUID coin, String refType, UUID refId, String clientTransferId) {
        jdbc.sql("""
                        insert into wallet_ledger_entry (entry_id, user_id, wallet_type, coin_id, entry_type, amount,
                                                         balance_after_amount, ref_type, ref_id, client_transfer_id,
                                                         created_at, updated_at)
                        values (?, ?, ?, ?, ?, -1, 0, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), user, wallet, coin, type, refType, refId, clientTransferId, AT, AT)
                .update();
    }

    private UUID insertOrderList(Trader trader) {
        UUID list = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_order_list (list_id, account_id, pair_id, market_type, list_type, list_status,
                                                      list_client_order_id, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'OCO', 'EXECUTING', ?, ?, ?)""")
                .params(list, trader.account(), pair, list.toString(), AT, AT)
                .update();
        return list;
    }

    private UUID insertOrder(Trader trader, String clientOrderId, UUID list) {
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_order (order_id, account_id, pair_id, market_type, client_order_id, list_id,
                                                 side, order_type, orig_quantity, executed_quantity, cum_quote_amount,
                                                 reduce_only, close_position, order_status, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', ?, ?, 'BUY', 'MARKET', 1, 0, 0, false, false, 'NEW', ?, ?)""")
                .params(order, trader.account(), pair, clientOrderId, list, AT, AT)
                .update();
        return order;
    }

    private UUID insertFill(Trader trader, UUID order) {
        UUID fill = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_fill (fill_id, order_id, account_id, pair_id, market_type, side, fill_price,
                                                fill_quantity, quote_amount, fee_amount, fee_coin_id, liquidity,
                                                fill_source, traded_at, created_at, updated_at)
                        values (?, ?, ?, ?, 'SPOT', 'BUY', 100, 1, 100, 0.1, ?, 'TAKER', 'LIVE', ?, ?, ?)""")
                .params(fill, order, trader.account(), pair, usdt, AT, AT, AT)
                .update();
        return fill;
    }

    private void insertSnapshot(Trader trader) {
        jdbc.sql("""
                        insert into paper_equity_snapshot (snapshot_id, account_id, snapshot_date, spot_equity_amount,
                                                           futures_equity_amount, total_equity_amount, created_at,
                                                           updated_at)
                        values (?, ?, date '2026-10-20', 1000, 500, 1500, ?, ?)""").params(UUID.randomUUID(), trader.account(), AT, AT).update();
    }

    private UUID insertCoin(String symbol) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                .params(id, symbol, symbol, AT, AT)
                .update();
        return id;
    }

    private Object ownerOf(String table, String key, UUID id) {
        return jdbc.sql("select user_id from " + table + " where " + key + " = ?")
                .param(id)
                .query()
                .singleValue();
    }

    private long count(String query, UUID id) {
        return jdbc.sql(query).param(id).query(Long.class).single();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .placeholders(configuredFlyway.getConfiguration().getPlaceholders())
                .target(target)
                .load();
    }

    private record Trader(UUID user, UUID account, UUID usdtSpot) {}
}

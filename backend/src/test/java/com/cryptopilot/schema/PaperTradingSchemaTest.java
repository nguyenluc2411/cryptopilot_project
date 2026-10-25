package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules V21 and V47 put on the paper trading tables, each proved by a row the database has to refuse, next to
 * the nearest row it has to accept, so a refusal is known to come from the rule under test and not from a broken
 * fixture. Since V47 every row belongs to the Trader directly, a transfer is its pair of ledger entries and an order
 * may name the plan it carries out (D-117).
 *
 * <p>Every test runs in a transaction that is rolled back; the statement expected to fail is the last one, because a
 * failed statement leaves a PostgreSQL transaction unusable.
 *
 * <p>Rule: TR-02; Q-T1 to Q-T10; BR-21 (Spot holds no short and no leverage); D-117.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PaperTradingSchemaTest {

    private static final OffsetDateTime NOW =
            Instant.parse("2026-10-07T00:00:00Z").atOffset(ZoneOffset.UTC);

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private UUID pairId;
    private UUID usdtId;

    @BeforeEach
    void insertReferenceRows() {
        userId = insertUser("paper.trader@cryptopilot.test");
        usdtId = insertCoin("PUSDT");
        UUID base = insertCoin("PBTC");
        pairId = UUID.randomUUID();
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol,
                                                 is_spot_enabled, is_futures_enabled, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'PBTCPUSDT', true, true, 'ACTIVE', ?, ?)""").params(pairId, base, usdtId, NOW, NOW).update();
    }

    // ---------------------------------------------------------------- account

    @Test
    void aWalletCoin_isGrantedOnce() {
        insertLedger(userId, "INITIAL_GRANT", "SPOT", "USER", userId, null, 100);
        insertLedger(userId, "INITIAL_GRANT", "FUTURES", "USER", userId, null, 100);
        insertLedger(insertUser("other.grant@cryptopilot.test"), "INITIAL_GRANT", "SPOT", null, null, null, 100);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "INITIAL_GRANT", "SPOT", "USER", userId, null, 100));
    }

    @Test
    void deletingATraderWithAPaperWallet_isRefused() {
        insertBalance("SPOT", usdtId, BigDecimal.TEN, BigDecimal.ZERO);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("delete from user_account where user_id = ?")
                        .param(userId)
                        .update());
    }

    @Test
    void theFoldedTables_areGone_andTheirRowsKeptAside() {
        assertThat(jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = 'public' and table_name like 'paper\\_%'""").query(Long.class).single())
                .as("no paper_ table is left: V47 renamed, folded or dropped each one")
                .isZero();
        assertThat(jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = 'public'
                           and table_name in ('legacy_paper_account', 'legacy_paper_transfer')""").query(Long.class).single()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- wallets

    @Test
    void aBalance_mayBeZeroButNeverNegative() {
        insertBalance("SPOT", usdtId, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertBalance("FUTURES", usdtId, new BigDecimal("-0.00000001"), BigDecimal.ZERO));
    }

    @Test
    void aCoin_isHeldOncePerWallet() {
        insertBalance("SPOT", usdtId, BigDecimal.TEN, BigDecimal.ZERO);
        insertBalance("FUTURES", usdtId, BigDecimal.TEN, BigDecimal.ZERO);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertBalance("SPOT", usdtId, BigDecimal.ONE, BigDecimal.ZERO));
    }

    @Test
    void aTransfer_isADebitAndACreditSharingItsIdAndKey() {
        UUID transfer = UUID.randomUUID();
        insertLedger(userId, "TRANSFER", "SPOT", "TRANSFER", transfer, "retry-1", -100);

        assertThat(insertLedger(userId, "TRANSFER", "FUTURES", "TRANSFER", transfer, "retry-1", 100))
                .isNotNull();
    }

    @Test
    void aClientTransferId_isOneTransferPerTrader() {
        insertLedger(userId, "TRANSFER", "SPOT", "TRANSFER", UUID.randomUUID(), "retry-1", -100);
        UUID other = insertUser("other.transfer@cryptopilot.test");
        insertLedger(other, "TRANSFER", "SPOT", "TRANSFER", UUID.randomUUID(), "retry-1", -100);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() ->
                        insertLedger(userId, "TRANSFER", "FUTURES", "TRANSFER", UUID.randomUUID(), "retry-1", -100));
    }

    @Test
    void aTransferEntry_carriesItsKey() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "TRANSFER", "SPOT", "TRANSFER", UUID.randomUUID(), null, -100));
    }

    @Test
    void anotherEntry_carriesNoTransferKey() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "TRADE", "SPOT", "FILL", UUID.randomUUID(), "retry-1", 100));
    }

    @Test
    void aTransferEntry_pointsAtItsTransfer() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "TRANSFER", "SPOT", "FILL", UUID.randomUUID(), "retry-1", -100));
    }

    @Test
    void theGrant_pointsAtItsTrader_andNoLongerAtAnAccount() {
        insertLedger(userId, "INITIAL_GRANT", "SPOT", "USER", userId, null, 100);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "INITIAL_GRANT", "FUTURES", "ACCOUNT", userId, null, 100));
    }

    @Test
    void aLedgerEntry_namesBothTheKindAndTheIdOfItsCause_orNeither() {
        insertLedger(userId, "INITIAL_GRANT", "SPOT", null, null, null, 100);
        insertLedger(userId, "TRANSFER", "SPOT", "TRANSFER", UUID.randomUUID(), "retry-1", -100);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger(userId, "TRADE", "SPOT", "FILL", null, null, 100));
    }

    // ---------------------------------------------------------------- orders

    @Test
    void BR21_aSpotOrder_hasNoPositionSide() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertMarketOrder("SPOT", "BOTH", false));
    }

    @Test
    void BR21_aSpotOrder_cannotBeReduceOnly() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertMarketOrder("SPOT", null, true));
    }

    @Test
    void aFuturesOrder_namesItsPositionSide() {
        insertMarketOrder("FUTURES", "BOTH", true);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertMarketOrder("FUTURES", null, false));
    }

    @Test
    void aLimitOrder_needsItsPriceAndTimeInForce() {
        insertOrder("LIMIT", new BigDecimal("60000"), "GTC", null, null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("LIMIT", new BigDecimal("60000"), null, null, null));
    }

    @Test
    void aMarketOrder_carriesNoLimitPrice() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("MARKET", new BigDecimal("60000"), "GTC", null, null));
    }

    @Test
    void aSpotStop_needsItsTriggerAndWhichWayItFires() {
        insertOrder("STOP_LOSS", null, null, new BigDecimal("58000"), "AT_OR_BELOW");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("STOP_LOSS", null, null, new BigDecimal("58000"), null));
    }

    @Test
    void aSpotStop_needsAStopPriceOrATrailingDelta() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("STOP_LOSS", null, null, null, "AT_OR_BELOW"));
    }

    @Test
    void aSpotLimitStop_hasAPriceAndATimeInForce() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("STOP_LOSS_LIMIT", null, null, new BigDecimal("58000"), "AT_OR_BELOW"));
    }

    @Test
    void anOrder_cannotExecuteMoreThanItsQuantity() {
        UUID orderId = insertMarketOrder("SPOT", null, false);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update trade_order set executed_quantity = 2 where order_id = ?")
                        .param(orderId)
                        .update());
    }

    @Test
    void aFinishedOrder_recordsWhenItFinished() {
        UUID orderId = insertMarketOrder("SPOT", null, false);
        jdbc.sql("""
                        update trade_order set order_status = 'FILLED', executed_quantity = 1, closed_at = ?
                         where order_id = ?""").params(NOW, orderId).update();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update trade_order set closed_at = null where order_id = ?")
                        .param(orderId)
                        .update());
    }

    @Test
    void aClientOrderId_isOneOrderPerTrader() {
        insertOrderWithClientId(userId, "web-1");
        insertOrderWithClientId(insertUser("other@cryptopilot.test"), "web-1");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrderWithClientId(userId, "web-1"));
    }

    @Test
    void anOrder_namesAStoredPlan_orNone() {
        UUID orderId = insertMarketOrder("SPOT", null, false);

        assertThat(jdbc.sql("select plan_id is null from trade_order where order_id = ?")
                        .param(orderId)
                        .query(Boolean.class)
                        .single())
                .as("an order placed on the trading screen carries out no plan")
                .isTrue();
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update trade_order set plan_id = ? where order_id = ?")
                        .params(UUID.randomUUID(), orderId)
                        .update());
    }

    @Test
    void BR21_aSpotFill_realisesNoProfitOrLoss() {
        UUID orderId = insertMarketOrder("SPOT", null, false);
        insertFill(orderId, "SPOT", null, null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertFill(orderId, "SPOT", null, BigDecimal.TEN));
    }

    // ---------------------------------------------------------------- helpers

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                                  created_at, updated_at)
                        values (?, ?, 'hash', 'TRADER', 'ACTIVE', ?, ?)""").params(id, email, NOW, NOW).update();
        return id;
    }

    private UUID insertCoin(String symbol) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into coin (coin_id, symbol, coin_name, created_at, updated_at)
                        values (?, ?, ?, ?, ?)""").params(id, symbol, symbol + " coin", NOW, NOW).update();
        return id;
    }

    private void insertBalance(String wallet, UUID coinId, BigDecimal free, BigDecimal locked) {
        jdbc.sql("""
                        insert into wallet_balance (balance_id, user_id, wallet_type, coin_id, free_amount,
                                                    locked_amount, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), userId, wallet, coinId, free, locked, NOW, NOW)
                .update();
    }

    private UUID insertLedger(
            UUID owner, String type, String wallet, String refType, UUID refId, String clientTransferId, int amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into wallet_ledger_entry (entry_id, user_id, wallet_type, coin_id, entry_type, amount,
                                                         balance_after_amount, ref_type, ref_id, client_transfer_id,
                                                         created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, 100, ?, ?, ?, ?, ?)""")
                .params(id, owner, wallet, usdtId, type, amount, refType, refId, clientTransferId, NOW, NOW)
                .update();
        return id;
    }

    private UUID insertMarketOrder(String market, String positionSide, boolean reduceOnly) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trade_order (order_id, user_id, pair_id, market_type, client_order_id, side,
                                                 position_side, order_type, orig_quantity, executed_quantity,
                                                 cum_quote_amount, reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'BUY', ?, 'MARKET', 1, 0, 0, ?, false, 'NEW', ?, ?)""")
                .params(id, userId, pairId, market, id.toString(), positionSide, reduceOnly, NOW, NOW)
                .update();
        return id;
    }

    private void insertOrder(
            String type, BigDecimal limitPrice, String timeInForce, BigDecimal stopPrice, String triggerCondition) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trade_order (order_id, user_id, pair_id, market_type, client_order_id, side,
                                                 order_type, time_in_force, limit_price, stop_price,
                                                 trigger_condition, orig_quantity, executed_quantity,
                                                 cum_quote_amount, reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'SPOT', ?, 'SELL', ?, ?, ?, ?, ?, 1, 0, 0, false, false, 'NEW', ?, ?)""")
                .params(
                        id,
                        userId,
                        pairId,
                        id.toString(),
                        type,
                        timeInForce,
                        limitPrice,
                        stopPrice,
                        triggerCondition,
                        NOW,
                        NOW)
                .update();
    }

    private void insertOrderWithClientId(UUID owner, String clientOrderId) {
        jdbc.sql("""
                        insert into trade_order (order_id, user_id, pair_id, market_type, client_order_id, side,
                                                 order_type, orig_quantity, executed_quantity, cum_quote_amount,
                                                 reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'SPOT', ?, 'BUY', 'MARKET', 1, 0, 0, false, false, 'NEW', ?, ?)""")
                .params(UUID.randomUUID(), owner, pairId, clientOrderId, NOW, NOW)
                .update();
    }

    private void insertFill(UUID orderId, String market, String positionSide, BigDecimal realizedPnl) {
        jdbc.sql("""
                        insert into order_fill (fill_id, order_id, user_id, pair_id, market_type, side,
                                                position_side, fill_price, fill_quantity, quote_amount, fee_amount,
                                                fee_coin_id, liquidity, realized_pnl_amount, fill_source,
                                                traded_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'BUY', ?, 60000, 0.01, 600, 0.6, ?, 'TAKER', ?, 'LIVE', ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        orderId,
                        userId,
                        pairId,
                        market,
                        positionSide,
                        usdtId,
                        realizedPnl,
                        NOW,
                        NOW,
                        NOW)
                .update();
    }
}

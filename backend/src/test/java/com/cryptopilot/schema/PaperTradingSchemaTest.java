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
 * The rules V21 puts on the paper trading tables, each proved by a row the database has to refuse, next to the
 * nearest row it has to accept, so a refusal is known to come from the rule under test and not from a broken
 * fixture.
 *
 * <p>Every test runs in a transaction that is rolled back; the statement expected to fail is the last one, because a
 * failed statement leaves a PostgreSQL transaction unusable.
 *
 * <p>Rule: TR-02; Q-T1 to Q-T10; BR-21 (Spot holds no short and no leverage).
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
    private UUID accountId;
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
        accountId = insertAccount(userId);
    }

    // ---------------------------------------------------------------- account

    @Test
    void aTrader_hasOneAccount() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> insertAccount(userId));
    }

    @Test
    void deletingATraderWithAPaperAccount_isRefused() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("delete from user_account where user_id = ?")
                        .param(userId)
                        .update());
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
    void aTransfer_movesBetweenTwoDifferentWallets() {
        insertTransfer("SPOT", "FUTURES");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertTransfer("SPOT", "SPOT"));
    }

    @Test
    void aClientTransferId_isOneTransferPerAccount() {
        insertTransfer(accountId, "retry-1", "SPOT", "FUTURES");
        insertTransfer(insertAccount(insertUser("other.transfer@cryptopilot.test")), "retry-1", "SPOT", "FUTURES");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertTransfer(accountId, "retry-1", "SPOT", "FUTURES"));
    }

    @Test
    void aLedgerEntry_namesBothTheKindAndTheIdOfItsCause_orNeither() {
        insertLedger("INITIAL_GRANT", null, null);
        insertLedger("TRANSFER", "TRANSFER", UUID.randomUUID());

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLedger("TRADE", "FILL", null));
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
    void aStopOrder_needsItsTriggerAndWhichWayItFires() {
        insertOrder("STOP_MARKET", null, null, new BigDecimal("58000"), "AT_OR_BELOW");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder("STOP_MARKET", null, null, new BigDecimal("58000"), null));
    }

    @Test
    void anOrder_cannotExecuteMoreThanItsQuantity() {
        UUID orderId = insertMarketOrder("SPOT", null, false);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update paper_order set executed_quantity = 2 where order_id = ?")
                        .param(orderId)
                        .update());
    }

    @Test
    void aFinishedOrder_recordsWhenItFinished() {
        UUID orderId = insertMarketOrder("SPOT", null, false);
        jdbc.sql("""
                        update paper_order set order_status = 'FILLED', executed_quantity = 1, closed_at = ?
                         where order_id = ?""").params(NOW, orderId).update();

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("update paper_order set closed_at = null where order_id = ?")
                        .param(orderId)
                        .update());
    }

    @Test
    void aClientOrderId_isOneOrderPerAccount() {
        insertOrderWithClientId(accountId, "web-1");
        insertOrderWithClientId(insertAccount(insertUser("other@cryptopilot.test")), "web-1");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrderWithClientId(accountId, "web-1"));
    }

    @Test
    void BR21_aSpotFill_realisesNoProfitOrLoss() {
        UUID orderId = insertMarketOrder("SPOT", null, false);
        insertFill(orderId, "SPOT", null, null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertFill(orderId, "SPOT", null, BigDecimal.TEN));
    }

    // ---------------------------------------------------------------- positions

    @Test
    void onePositionIsOpen_perPairAndSide() {
        insertOpenPosition("LONG", "LONG", "CROSS", null);
        insertOpenPosition("SHORT", "SHORT", "CROSS", null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOpenPosition("LONG", "LONG", "CROSS", null));
    }

    @Test
    void closedPositions_areNotLimited() {
        UUID first = insertOpenPosition("BOTH", "LONG", "CROSS", null);
        close(first);

        assertThat(insertOpenPosition("BOTH", "SHORT", "CROSS", null)).isNotNull();
    }

    @Test
    void aHedgePosition_isOnItsOwnSide() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOpenPosition("LONG", "SHORT", "CROSS", null));
    }

    @Test
    void anIsolatedPosition_carriesItsMargin_andACrossOneDoesNot() {
        insertOpenPosition("LONG", "LONG", "ISOLATED", new BigDecimal("600"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOpenPosition("SHORT", "SHORT", "CROSS", new BigDecimal("600")));
    }

    @Test
    void aClosedPosition_holdsNothingAndSaysWhy() {
        UUID positionId = insertOpenPosition("BOTH", "LONG", "CROSS", null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.sql("""
                        update paper_position set position_status = 'CLOSED', position_quantity = 0,
                               exit_price = 61000, closed_at = ?
                         where position_id = ?""").params(NOW, positionId).update());
    }

    @Test
    void aSettlement_isPaidOncePerPosition() {
        UUID positionId = insertOpenPosition("BOTH", "LONG", "CROSS", null);
        insertFunding(positionId);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> insertFunding(positionId));
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

    private UUID insertAccount(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_account (account_id, user_id, position_mode,
                                                   created_at, updated_at)
                        values (?, ?, 'ONE_WAY', ?, ?)""").params(id, owner, NOW, NOW).update();
        return id;
    }

    private void insertBalance(String wallet, UUID coinId, BigDecimal free, BigDecimal locked) {
        jdbc.sql("""
                        insert into paper_balance (balance_id, account_id, wallet_type, coin_id, free_amount,
                                                   locked_amount, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), accountId, wallet, coinId, free, locked, NOW, NOW)
                .update();
    }

    private void insertTransfer(String from, String to) {
        insertTransfer(accountId, UUID.randomUUID().toString(), from, to);
    }

    private void insertTransfer(UUID account, String clientTransferId, String from, String to) {
        jdbc.sql("""
                        insert into paper_transfer (transfer_id, account_id, client_transfer_id, coin_id, from_wallet,
                                                    to_wallet, amount, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, 100, ?, ?)""")
                .params(UUID.randomUUID(), account, clientTransferId, usdtId, from, to, NOW, NOW)
                .update();
    }

    private void insertLedger(String type, String refType, UUID refId) {
        jdbc.sql("""
                        insert into paper_ledger_entry (entry_id, account_id, wallet_type, coin_id, entry_type,
                                                        amount, balance_after_amount, ref_type, ref_id,
                                                        created_at, updated_at)
                        values (?, ?, 'SPOT', ?, ?, 100, 100, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), accountId, usdtId, type, refType, refId, NOW, NOW)
                .update();
    }

    private UUID insertMarketOrder(String market, String positionSide, boolean reduceOnly) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_order (order_id, account_id, pair_id, market_type, client_order_id, side,
                                                 position_side, order_type, orig_quantity, executed_quantity,
                                                 cum_quote_amount, reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'BUY', ?, 'MARKET', 1, 0, 0, ?, false, 'NEW', ?, ?)""")
                .params(id, accountId, pairId, market, id.toString(), positionSide, reduceOnly, NOW, NOW)
                .update();
        return id;
    }

    private void insertOrder(
            String type, BigDecimal limitPrice, String timeInForce, BigDecimal stopPrice, String triggerCondition) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_order (order_id, account_id, pair_id, market_type, client_order_id, side,
                                                 order_type, time_in_force, limit_price, stop_price,
                                                 trigger_condition, orig_quantity, executed_quantity,
                                                 cum_quote_amount, reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'SPOT', ?, 'SELL', ?, ?, ?, ?, ?, 1, 0, 0, false, false, 'NEW', ?, ?)""")
                .params(
                        id,
                        accountId,
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

    private void insertOrderWithClientId(UUID account, String clientOrderId) {
        jdbc.sql("""
                        insert into paper_order (order_id, account_id, pair_id, market_type, client_order_id, side,
                                                 order_type, orig_quantity, executed_quantity, cum_quote_amount,
                                                 reduce_only, close_position, order_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'SPOT', ?, 'BUY', 'MARKET', 1, 0, 0, false, false, 'NEW', ?, ?)""")
                .params(UUID.randomUUID(), account, pairId, clientOrderId, NOW, NOW)
                .update();
    }

    private void insertFill(UUID orderId, String market, String positionSide, BigDecimal realizedPnl) {
        jdbc.sql("""
                        insert into paper_fill (fill_id, order_id, account_id, pair_id, market_type, side,
                                                position_side, fill_price, fill_quantity, quote_amount, fee_amount,
                                                fee_coin_id, liquidity, realized_pnl_amount, fill_source,
                                                traded_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'BUY', ?, 60000, 0.01, 600, 0.6, ?, 'TAKER', ?, 'LIVE', ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        orderId,
                        accountId,
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

    private UUID insertOpenPosition(String side, String direction, String marginMode, BigDecimal isolatedMargin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_position (position_id, account_id, pair_id, position_side, direction,
                                                    margin_mode, leverage, position_quantity, max_quantity,
                                                    entry_price, isolated_margin_amount, realized_pnl_amount,
                                                    fee_amount, funding_amount, position_status,
                                                    opened_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, 10, 0.1, 0.1, 60000, ?, 0, 3, 0, 'OPEN', ?, ?, ?)""")
                .params(id, accountId, pairId, side, direction, marginMode, isolatedMargin, NOW, NOW, NOW)
                .update();
        return id;
    }

    private void close(UUID positionId) {
        jdbc.sql("""
                        update paper_position set position_status = 'CLOSED', position_quantity = 0,
                               exit_price = 61000, closed_at = ?, close_reason = 'MANUAL'
                         where position_id = ?""").params(NOW, positionId).update();
    }

    private void insertFunding(UUID positionId) {
        jdbc.sql("""
                        insert into paper_funding_payment (payment_id, account_id, position_id, pair_id, funding_time,
                                                           funding_rate, mark_price, position_quantity, amount,
                                                           created_at, updated_at)
                        values (?, ?, ?, ?, ?, 0.0001, 61000, 0.1, -0.61, ?, ?)""")
                .params(UUID.randomUUID(), accountId, positionId, pairId, NOW, NOW, NOW)
                .update();
    }
}

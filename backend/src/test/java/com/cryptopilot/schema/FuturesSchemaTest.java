package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
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
 * The rules V49 puts on the paper Futures tables, each proved by a row the database has to refuse next to the nearest
 * row it accepts. Every test runs in a transaction that is rolled back; the statement expected to fail is the last.
 *
 * <p>Rule: TR-14, TR-16; D-127.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class FuturesSchemaTest {

    private static final OffsetDateTime NOW =
            Instant.parse("2026-10-28T00:00:00Z").atOffset(ZoneOffset.UTC);

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private UUID pairId;

    @BeforeEach
    void insertReferenceRows() {
        userId = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status,
                                                  created_at, updated_at)
                        values (?, ?, 'hash', 'TRADER', 'ACTIVE', ?, ?)""").params(userId, userId + "@v49.invalid", NOW, NOW).update();
        UUID base = coin("FBTC");
        UUID quote = coin("FUSDT");
        pairId = UUID.randomUUID();
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol,
                                                 is_spot_enabled, is_futures_enabled, pair_status,
                                                 created_at, updated_at)
                        values (?, ?, ?, 'FBTCFUSDT', true, true, 'ACTIVE', ?, ?)""").params(pairId, base, quote, NOW, NOW).update();
    }

    @Test
    void TR16_onlyOneOpenPosition_perPairAndSide() {
        position("BOTH", "LONG", "CROSSED", null, "OPEN");
        position("BOTH", "SHORT", "CROSSED", null, "CLOSED");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> position("BOTH", "SHORT", "CROSSED", null, "OPEN"));
    }

    @Test
    void TR16_anIsolatedPosition_hasAWalletNeverBelowZero() {
        position("BOTH", "LONG", "ISOLATED", "0", "OPEN");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> position("LONG", "LONG", "ISOLATED", "-0.00000001", "OPEN"));
    }

    @Test
    void TR16_aCrossPosition_hasNoIsolatedWallet() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> position("BOTH", "LONG", "CROSSED", "0", "OPEN"));
    }

    @Test
    void TR14_inHedgeMode_theSideIsTheDirection() {
        position("LONG", "LONG", "CROSSED", null, "OPEN");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> position("SHORT", "LONG", "CROSSED", null, "OPEN"));
    }

    @Test
    void TR16_anAdjustment_isNeverZero() {
        UUID position = position("BOTH", "LONG", "ISOLATED", "600", "OPEN");
        adjustment(position, "600");

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> adjustment(position, "0"));
    }

    @Test
    void TR14_aLeverage_isBetweenOneAnd125() {
        symbolConfig(125);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> symbolConfig(126));
    }

    @Test
    void TR16_aFuturesFill_belongsToAPosition() {
        UUID order = order();
        UUID position = position("BOTH", "LONG", "CROSSED", null, "OPEN");
        fill(order, position);

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> fill(order, null));
    }

    @Test
    void TR24_aFundingFee_isRecordedOncePerPositionAndSettlement() {
        UUID ref = UUID.randomUUID();
        UUID coin = coin("FFUND");
        ledger(coin, "FUNDING_FEE", ref, "-0.61");
        ledger(coin, "INSURANCE_COVER", UUID.randomUUID(), "0.11");

        assertThat(jdbc.sql("select count(*) from wallet_ledger_entry where user_id = ?")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> ledger(coin, "FUNDING_FEE", ref, "-0.61"));
    }

    // ---------------------------------------------------------------- rows

    private UUID coin(String symbol) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into coin (coin_id, symbol, coin_name, created_at, updated_at)
                        values (?, ?, ?, ?, ?)""").params(id, symbol, symbol, NOW, NOW).update();
        return id;
    }

    private UUID position(String side, String direction, String marginType, String wallet, String status) {
        UUID id = UUID.randomUUID();
        boolean closed = "CLOSED".equals(status);
        jdbc.sql("""
                        insert into futures_position (position_id, user_id, pair_id, position_side, direction,
                                                      margin_type, leverage, quantity, max_quantity, entry_price,
                                                      isolated_wallet_amount, position_status, close_reason, opened_at,
                                                      closed_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, 10, ?, 0.1, 60000, ?::numeric, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        userId,
                        pairId,
                        side,
                        direction,
                        marginType,
                        closed ? 0 : 0.1,
                        wallet,
                        status,
                        closed ? "MANUAL" : null,
                        NOW,
                        closed ? NOW : null,
                        NOW,
                        NOW)
                .update();
        return id;
    }

    private void adjustment(UUID position, String amount) {
        jdbc.sql("""
                        insert into futures_margin_adjustment (adjustment_id, position_id, user_id, pair_id,
                                                               adjustment_type, amount, iw_after_amount, created_at,
                                                               updated_at)
                        values (?, ?, ?, ?, 'OPEN', ?::numeric, 600, ?, ?)""")
                .params(UUID.randomUUID(), position, userId, pairId, amount, NOW, NOW)
                .update();
    }

    private void symbolConfig(int leverage) {
        jdbc.sql("""
                        insert into futures_symbol_config (symbol_config_id, user_id, pair_id, leverage, margin_type,
                                                           created_at, updated_at)
                        values (?, ?, ?, ?, 'CROSSED', ?, ?)""")
                .params(UUID.randomUUID(), userId, pairId, leverage, NOW, NOW)
                .update();
        jdbc.sql("delete from futures_symbol_config where user_id = ?")
                .param(userId)
                .update();
    }

    private UUID order() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into trade_order (order_id, user_id, pair_id, market_type, client_order_id, side,
                                                 position_side, order_type, orig_quantity, executed_quantity,
                                                 cum_quote_amount, reduce_only, close_position, order_status,
                                                 closed_at, created_at, updated_at)
                        values (?, ?, ?, 'FUTURES', ?, 'BUY', 'BOTH', 'MARKET', 0.1, 0.1, 6000, false, false,
                                'FILLED', ?, ?, ?)""")
                .params(id, userId, pairId, "v49-" + id.toString().substring(0, 8), NOW, NOW, NOW)
                .update();
        return id;
    }

    private void fill(UUID order, UUID position) {
        UUID usdt = jdbc.sql("select quote_coin_id from crypto_pair where pair_id = ?")
                .param(pairId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        insert into order_fill (fill_id, order_id, user_id, pair_id, market_type, side, position_side,
                                                fill_price, fill_quantity, quote_amount, fee_amount, fee_coin_id,
                                                liquidity, realized_pnl_amount, fill_source, position_id, traded_at,
                                                created_at, updated_at)
                        values (?, ?, ?, ?, 'FUTURES', 'BUY', 'BOTH', 60000, 0.1, 6000, 0, ?, 'TAKER', 0, 'LIVE', ?,
                                ?, ?, ?)""")
                .params(UUID.randomUUID(), order, userId, pairId, usdt, position, NOW, NOW, NOW)
                .update();
    }

    private void ledger(UUID coin, String type, UUID ref, String amount) {
        jdbc.sql("""
                        insert into wallet_ledger_entry (entry_id, user_id, wallet_type, coin_id, entry_type, amount,
                                                         balance_after_amount, ref_type, ref_id, created_at,
                                                         updated_at)
                        values (?, ?, 'FUTURES', ?, ?, ?::numeric, 0, ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        userId,
                        coin,
                        type,
                        amount,
                        "FUNDING_FEE".equals(type) ? "FUNDING" : "POSITION",
                        ref,
                        NOW,
                        NOW)
                .update();
    }
}

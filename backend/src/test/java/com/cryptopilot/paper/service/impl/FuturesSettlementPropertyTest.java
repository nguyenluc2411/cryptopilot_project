package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.dto.request.FuturesClosePositionRequest;
import com.cryptopilot.paper.dto.request.FuturesLeverageRequest;
import com.cryptopilot.paper.dto.request.FuturesMarginTypeRequest;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.request.FuturesPositionMarginRequest;
import com.cryptopilot.paper.dto.request.TransferRequest;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.service.FuturesConfigService;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.FuturesPositionService;
import com.cryptopilot.paper.service.PaperAccountService;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The money invariants of paper Futures (plan 4.3, 14.2) on the database: random sequences of what a Trader and the
 * market do, through the services as the API calls them, each followed by a check of every invariant. Two pairs, one
 * starting cross and one isolated at 50x; marks that drift and often jump by up to 40 %, so losses beyond an isolated
 * wallet and a cross wallet below zero happen (no liquidation exists yet to stop them). A refusal of the exchange's
 * rules is a valid outcome of a step; any other failure fails the sequence.
 *
 * <ul>
 *   <li>The Futures wallet is the sum of its ledger, and locks nothing.
 *   <li>Every isolated wallet is the sum of its adjustment rows; a closed position has none left.
 *   <li>With no cross position open, the cross wallet is never below zero: an isolated loss never reaches it.
 *   <li>A position's size is what its fills opened less what they closed, its realised profit the sum of theirs; an
 *       order's executed quantity is the sum of its fills.
 * </ul>
 *
 * <p>Rule: TR-16, TR-17, TR-18; D-127.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FuturesSettlementPropertyTest {

    /** The PRO package of 30 days that V11 seeded, which has the Futures feature. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    private static final int STEPS = 120;

    private static final AtomicLong TICKS = new AtomicLong();

    @Autowired
    private JdbcClient sql;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private TradingMarketData trading;

    @Autowired
    private PaperAccountService paper;

    @Autowired
    private FuturesConfigService config;

    @Autowired
    private FuturesOrderService orders;

    @Autowired
    private FuturesPositionService positions;

    @Autowired
    private PaperMatchingService matching;

    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> symbols = new ArrayList<>();
    private final List<BigDecimal> marks = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID trader;
    private Random random;

    @BeforeEach
    void insertPairs() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        for (int i = 0; i < 2; i++) {
            String symbol = "FP" + i + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
            UUID pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", i);
            sql.sql("""
                            update crypto_pair set futures_tick_size = 0.01, futures_step_size = 0.001,
                                   futures_min_notional = 5
                             where pair_id = ?""").param(pair).update();
            bracket(pair, 1, "0", "50000", 125, "0.004", "0");
            bracket(pair, 2, "50000", "600000", 100, "0.005", "50");
            bracket(pair, 3, "600000", "3000000", 20, "0.0065", "950");
            pairs.add(pair);
            symbols.add(symbol);
            marks.add(new BigDecimal("100"));
        }
    }

    @AfterEach
    void removeRows() {
        if (trader != null) {
            for (String table : List.of(
                    "futures_margin_adjustment",
                    "order_fill",
                    "futures_position",
                    "futures_symbol_config",
                    "futures_account_config",
                    "trade_order",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(trader).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        trading.retainOnly(MarketType.FUTURES, List.of());
        for (UUID pair : pairs) {
            sql.sql("delete from leverage_bracket where pair_id = ?")
                    .param(pair)
                    .update();
            new MarketTestData(sql, Instant.now()).remove(pair);
        }
        for (String coin : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("""
                                delete from coin where symbol = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)""").param(coin).update();
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    void TR16_randomSequences_keepTheMoneyInvariants_onTheDatabase(long seed) {
        random = new Random(seed);
        trader = trader();
        for (int i = 0; i < pairs.size(); i++) {
            market(i);
        }
        attempt(() -> config.changeLeverage(trader, new FuturesLeverageRequest(symbols.get(1), 50)));
        attempt(() ->
                config.changeMarginType(trader, new FuturesMarginTypeRequest(symbols.get(1), MarginType.ISOLATED)));
        for (int step = 0; step < STEPS; step++) {
            randomStep();
            assertInvariants("seed " + seed + " step " + step);
        }
        // A sequence the rules refused throughout would prove nothing.
        assertThat(sql.sql("select count(*) from order_fill where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .as("seed %d executes something", seed)
                .isPositive();
    }

    private void randomStep() {
        int pair = random.nextInt(pairs.size());
        String symbol = symbols.get(pair);
        int action = random.nextInt(100);
        if (action < 20) {
            move(pair);
        } else if (action < 45) {
            attempt(() ->
                    orders.place(trader, order(symbol, side(), OrderType.MARKET, null, quantity(), reduceOnly())));
        } else if (action < 60) {
            BigDecimal price = marks.get(pair)
                    .multiply(BigDecimal.valueOf(0.97 + random.nextDouble() * 0.06))
                    .setScale(2, RoundingMode.HALF_UP);
            attempt(() ->
                    orders.place(trader, order(symbol, side(), OrderType.LIMIT, price, quantity(), reduceOnly())));
        } else if (action < 70) {
            fillResting();
        } else if (action < 78) {
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(200));
            attempt(() -> positions.changeMargin(
                    trader, new FuturesPositionMarginRequest(symbol, null, amount, 1 + random.nextInt(2))));
        } else if (action < 85) {
            BigDecimal percent = BigDecimal.valueOf(1 + random.nextInt(100));
            attempt(() ->
                    positions.close(trader, new FuturesClosePositionRequest(symbol, OrderType.MARKET, null, percent)));
        } else if (action < 90) {
            int leverage = 1 + random.nextInt(100);
            attempt(() -> config.changeLeverage(trader, new FuturesLeverageRequest(symbol, leverage)));
            MarginType type = random.nextBoolean() ? MarginType.ISOLATED : MarginType.CROSSED;
            attempt(() -> config.changeMarginType(trader, new FuturesMarginTypeRequest(symbol, type)));
        } else if (action < 95) {
            WalletType from = random.nextBoolean() ? WalletType.FUTURES : WalletType.SPOT;
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(3000));
            attempt(() -> paper.transfer(trader, new TransferRequest("USDT", from, amount, null)));
        } else {
            attempt(() -> orders.cancelAll(trader, symbol));
        }
    }

    /** The mark drifts by up to 2 %, or one time in four jumps by up to 40 %; the book follows it. */
    private void move(int pair) {
        double change = random.nextInt(4) == 0 ? (random.nextDouble() - 0.5) * 0.8 : (random.nextDouble() - 0.5) * 0.04;
        BigDecimal mark = marks.get(pair)
                .multiply(BigDecimal.valueOf(1 + change))
                .max(new BigDecimal("5"))
                .setScale(2, RoundingMode.HALF_UP);
        marks.set(pair, mark);
        market(pair);
    }

    /** The engine fills one of the waiting orders, at its limit price. */
    private void fillResting() {
        List<Map<String, Object>> open = sql.sql("""
                        select order_id, pair_id, side, limit_price from trade_order
                         where user_id = ? and market_type = 'FUTURES'
                           and order_status in ('NEW', 'PARTIALLY_FILLED')
                         order by created_at""").param(trader).query().listOfRows();
        if (open.isEmpty()) {
            return;
        }
        Map<String, Object> chosen = open.get(random.nextInt(open.size()));
        attempt(() -> matching.fill(
                new RestingOrder(
                        (UUID) chosen.get("order_id"),
                        trader,
                        MarketType.FUTURES,
                        (UUID) chosen.get("pair_id"),
                        OrderSide.valueOf((String) chosen.get("side")),
                        (BigDecimal) chosen.get("limit_price"),
                        Instant.now().minusSeconds(1)),
                Instant.now(),
                FillSource.LIVE));
    }

    private void assertInvariants(String at) {
        BigDecimal wallet = sql.sql("""
                        select coalesce(sum(b.free_amount), 0) from wallet_balance b join coin c on c.coin_id = b.coin_id
                         where b.user_id = ? and b.wallet_type = 'FUTURES' and c.symbol = 'USDT'""").param(trader).query(BigDecimal.class).single();
        BigDecimal ledger = sql.sql("""
                        select coalesce(sum(amount), 0) from wallet_ledger_entry
                         where user_id = ? and wallet_type = 'FUTURES'""").param(trader).query(BigDecimal.class).single();
        assertThat(ledger).as("%s: Futures ledger = wallet", at).isEqualByComparingTo(wallet);
        assertThat(sql.sql("""
                                select coalesce(sum(locked_amount), 0) from wallet_balance
                                 where user_id = ? and wallet_type = 'FUTURES'""").param(trader).query(BigDecimal.class).single())
                .as("%s: the Futures wallet locks nothing", at)
                .isEqualByComparingTo("0");
        List<Map<String, Object>> rows = sql.sql("""
                        select p.position_id, p.margin_type, p.position_status, p.quantity, p.realized_pnl_amount,
                               coalesce(p.isolated_wallet_amount, 0) as iw,
                               (select coalesce(sum(a.amount), 0) from futures_margin_adjustment a
                                 where a.position_id = p.position_id) as adjusted,
                               (select coalesce(sum(case when (f.side = 'BUY') = (p.direction = 'LONG')
                                                         then f.fill_quantity else -f.fill_quantity end), 0)
                                  from order_fill f where f.position_id = p.position_id) as filled,
                               (select coalesce(sum(f.realized_pnl_amount), 0) from order_fill f
                                 where f.position_id = p.position_id) as realized
                          from futures_position p where p.user_id = ?""").param(trader).query().listOfRows();
        BigDecimal isolated = BigDecimal.ZERO;
        boolean crossOpen = false;
        for (Map<String, Object> row : rows) {
            BigDecimal iw = (BigDecimal) row.get("iw");
            assertThat(iw).as("%s: isolated wallet = its adjustments", at).isEqualByComparingTo((BigDecimal)
                    row.get("adjusted"));
            assertThat((BigDecimal) row.get("realized"))
                    .as("%s: realised = its fills'", at)
                    .isEqualByComparingTo((BigDecimal) row.get("realized_pnl_amount"));
            boolean open = "OPEN".equals(row.get("position_status"));
            assertThat((BigDecimal) row.get("filled"))
                    .as("%s: size = what its fills opened less what they closed", at)
                    .isEqualByComparingTo((BigDecimal) row.get("quantity"));
            if (open) {
                if ("ISOLATED".equals(row.get("margin_type"))) {
                    isolated = isolated.add(iw);
                } else {
                    crossOpen = true;
                }
            } else {
                assertThat(iw)
                        .as("%s: a closed position has no margin left", at)
                        .isEqualByComparingTo("0");
            }
        }
        if (!crossOpen) {
            assertThat(wallet.subtract(isolated))
                    .as("%s: with no cross position, the cross wallet is not below zero", at)
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
        List<Map<String, Object>> mismatched =
                sql.sql("""
                        select o.order_id from trade_order o
                         where o.user_id = ? and o.market_type = 'FUTURES'
                           and o.executed_quantity <> (select coalesce(sum(f.fill_quantity), 0) from order_fill f
                                                        where f.order_id = o.order_id)""").param(trader).query().listOfRows();
        assertThat(mismatched)
                .as("%s: an order's executed quantity = its fills'", at)
                .isEmpty();
    }

    /** Runs one action of the Trader; a refusal by the rules is a valid outcome, anything else is not. */
    private static void attempt(Runnable action) {
        try {
            action.run();
        } catch (BusinessException refused) {
            // The exchange's rules refused it: margin, filters, reduce-only, no price, and so on.
        }
    }

    private FuturesOrderRequest order(
            String symbol, OrderSide side, OrderType type, BigDecimal price, BigDecimal quantity, boolean reduceOnly) {
        return new FuturesOrderRequest(
                symbol,
                side,
                PositionSide.BOTH,
                type,
                type == OrderType.LIMIT ? TimeInForce.GTC : null,
                quantity,
                price,
                reduceOnly,
                null,
                null,
                null,
                null,
                null);
    }

    private OrderSide side() {
        return random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
    }

    private BigDecimal quantity() {
        return BigDecimal.valueOf(50 + random.nextInt(4000), 3);
    }

    private boolean reduceOnly() {
        return random.nextInt(5) == 0;
    }

    /** Streams the pair's mark and a two-level book around it. */
    private void market(int pair) {
        BigDecimal mark = marks.get(pair);
        BigDecimal tick = new BigDecimal("0.01");
        prices.record(
                MarketType.FUTURES,
                new MarkPriceMessage(
                        symbols.get(pair),
                        mark,
                        mark,
                        BigDecimal.ZERO,
                        Instant.now().plus(Duration.ofHours(1)),
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
        prices.flush();
        BigDecimal spread = mark.multiply(new BigDecimal("0.001"))
                .setScale(2, RoundingMode.UP)
                .max(tick);
        trading.recordDepth(new OrderBookDepth(
                MarketType.FUTURES,
                pairs.get(pair),
                TICKS.incrementAndGet(),
                List.of(
                        level(mark.subtract(tick), "3"),
                        level(mark.subtract(spread).subtract(tick), "50")),
                List.of(level(mark.add(tick), "3"), level(mark.add(spread).add(tick), "50")),
                Instant.now()));
    }

    private static PriceLevel level(BigDecimal price, String quantity) {
        return new PriceLevel(price, new BigDecimal(quantity));
    }

    private void bracket(UUID pair, int number, String floor, String cap, int leverage, String mmr, String cum) {
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        pair,
                        number,
                        new BigDecimal(floor),
                        new BigDecimal(cap),
                        leverage,
                        new BigDecimal(mmr),
                        new BigDecimal(cum),
                        Timestamp.from(Instant.now()))
                .update();
    }

    /** A Trader on the PRO plan with an opened paper account: 5,000 USDT in the Futures wallet. */
    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr16.invalid", now, now).update();
        Instant start = Instant.now().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        id,
                        PRO_PACKAGE,
                        "TR16-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
        paper.open(id);
        return id;
    }
}

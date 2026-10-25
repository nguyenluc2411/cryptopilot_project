package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.PaperLedgerInvariant;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Spot paper trading on a pair quoted in another coin than USDT, e.g. ETHBTC: every coin's cost stays in USDT, valued
 * at the quote coin's USDT rate when the fill executes, as Binance's wallet average cost does.
 *
 * <p>Each test makes its own coins: a quote coin {@code QX…} (BTC's role) with its pair {@code QX…USDT}, a base coin
 * {@code EX…} (ETH's role) with {@code EX…USDT}, and the cross pair {@code EX…QX…}. Prices are fixed tickers.
 *
 * <p>Rule: TR-13; D-96.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperCrossPairCostTest {

    private static final String PAPER = "/api/v1/paper";
    private static final List<String> GRANTED = List.of("USDT", "BTC", "ETH", "BNB");
    private static final AtomicLong TICKS = new AtomicLong();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TradingMarketData trading;

    @Autowired
    private PaperMatchingService matching;

    @MockitoSpyBean
    private MarketApi market;

    private final List<UUID> traders = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> coins = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private String quote;
    private String base;
    private UUID quoteUsdt;
    private UUID cross;

    @BeforeEach
    void insertPairs() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        String tag = Long.toString(System.nanoTime() % 1_000_000_000L);
        quote = "QX" + tag;
        base = "EX" + tag;
        quoteUsdt = pair(quote, "USDT", true, "0.01", "0.00000001", "5");
        pair(base, "USDT", true, "0.01", "0.001", "5");
        cross = pair(base, quote, true, "0.000001", "0.001", "0.0001");
        // No test reads the exchange: a replay finds no historical candle unless the test gives one.
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMinuteKlines(any(), any(), any());
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            for (String table : List.of(
                    "order_fill",
                    "trade_order",
                    "order_list",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        for (UUID pair : pairs) {
            sql.sql("delete from order_matching_watermark where pair_id = ?")
                    .param(pair)
                    .update();
            sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        }
        for (String coin : coins) {
            sql.sql("delete from coin where symbol = ?").param(coin).update();
        }
        for (String coin : GRANTED) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ?").param(coin).update();
            }
        }
        Set<String> keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void D96_aBuyOfACrossPair_costsWhatItPaidInUsdt_andThePaidCoinKeepsItsAverage() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        BigDecimal quoteAverage = averageCost(trader, quote);

        place(trader, market(cross, "BUY", "1")).andExpect(status().isCreated());

        // 0.05 QX at 60,000 USDT each: 3,000 USDT for 1 EX less its 0.001 fee.
        assertThat(spot(trader, base)).isEqualByComparingTo("0.999");
        assertThat(costBasis(trader, base)).isEqualByComparingTo("3000.00000000");
        assertThat(spot(trader, quote)).isEqualByComparingTo("0.0499");
        assertThat(costBasis(trader, quote)).isEqualByComparingTo("2996.99699700");
        assertThat(averageCost(trader, quote)).isCloseTo(quoteAverage, within(new BigDecimal("0.000001")));
        assertThat(lastFill(trader, cross))
                .containsEntry("quote_usdt_price_source", "LIVE")
                .satisfies(fill ->
                        assertThat((BigDecimal) fill.get("quote_usdt_price")).isEqualByComparingTo("60000"));
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aSaleOfACrossPair_addsItsNetAtTheUsdtRate_toTheQuoteCoin_andTheBaseKeepsItsAverage() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        place(trader, market(cross, "BUY", "1")).andExpect(status().isCreated());
        BigDecimal baseAverage = averageCost(trader, base);
        BigDecimal quoteCost = costBasis(trader, quote);

        place(trader, market(cross, "SELL", "0.5")).andExpect(status().isCreated());

        // 0.5 × 0.05 = 0.025 QX, less the 0.000025 fee: 0.024975 QX at 60,000 = 1,498.5 USDT.
        assertThat(costBasis(trader, quote)).isEqualByComparingTo(quoteCost.add(new BigDecimal("1498.5")));
        assertThat(averageCost(trader, base)).isCloseTo(baseAverage, within(new BigDecimal("0.000001")));
        assertThat(spot(trader, base)).isEqualByComparingTo("0.499");
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aRoundTripThroughACrossPair_keepsCostsAndEquityInUsdt() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + "USDT", "3000");
        price(base + quote, "0.05");
        BigDecimal opened = spotEquity(trader);

        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        assertThat(lastFill(trader, quoteUsdt))
                .containsEntry("quote_usdt_price_source", "ONE")
                .satisfies(fill ->
                        assertThat((BigDecimal) fill.get("quote_usdt_price")).isEqualByComparingTo("1"));
        place(trader, market(cross, "BUY", "1")).andExpect(status().isCreated());
        place(trader, market(cross, "SELL", "0.999")).andExpect(status().isCreated());

        // QX: 0.0499 left at 2,996.996997, then 0.04995 less 0.00004995 brought at 60,000: 2,994.003 more.
        assertThat(spot(trader, quote)).isEqualByComparingTo("0.09980005");
        assertThat(costBasis(trader, quote)).isEqualByComparingTo("5990.99999700");
        assertThat(costBasis(trader, base)).isZero();
        // 4,000 USDT and 0.09980005 QX worth 5,988.003: the three fees, 6 + 3 + 2.997 USDT, are gone.
        assertThat(spotEquity(trader)).isEqualByComparingTo(opened.subtract(new BigDecimal("11.997")));
        assertLedgerInvariant(trader);

        place(trader, market(quoteUsdt, "SELL", "0.09980005")).andExpect(status().isCreated());

        // 5,988.003 USDT less its 5.988003 fee.
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9982.014997");
        assertThat(costBasis(trader, quote)).isZero();
        assertThat(spotEquity(trader)).isEqualByComparingTo(opened.subtract(new BigDecimal("17.985003")));
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aRestingFillWithAStaleRate_isValuedAtTheLatestClosedMinute() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        String id = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        Instant now = Instant.now();
        staleQuoteUsdt(now.minus(Duration.ofMinutes(10)));
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(3));
        trading.recordClosedMinute(new MinuteKline(
                MarketType.SPOT,
                quoteUsdt,
                minute,
                new BigDecimal("58500"),
                new BigDecimal("59500"),
                new BigDecimal("59000"),
                true,
                minute.plusSeconds(60)));

        assertThat(matching.fill(resting(trader, id, "0.04"), now, FillSource.REPLAY))
                .isTrue();

        // 0.04 QX at the candle's close of 59,000.
        assertThat(costBasis(trader, base)).isEqualByComparingTo("2360.00000000");
        assertThat(lastFill(trader, cross))
                .containsEntry("quote_usdt_price_source", "CANDLE_1M")
                .satisfies(fill ->
                        assertThat((BigDecimal) fill.get("quote_usdt_price")).isEqualByComparingTo("59000"));
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aRestingFillWithNoRateAtAll_executes_andLeavesTheCostNotKnown() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + "USDT", "3000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        place(trader, marketOf(base + "USDT", "BUY", "0.01")).andExpect(status().isCreated());
        assertThat(costBasis(trader, base)).isNotNull();
        String id = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        redis.delete(priceKey(quote + "USDT"));

        assertThat(matching.fill(resting(trader, id, "0.04"), Instant.now(), FillSource.REPLAY))
                .isTrue();

        assertThat(spot(trader, base)).isEqualByComparingTo("1.00899");
        assertThat(costBasis(trader, base)).isNull();
        assertThat(lastFill(trader, cross))
                .containsEntry("quote_usdt_price_source", "NONE")
                .containsEntry("quote_usdt_price", null);
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aCrossLimitFilledInParts_costsEachPartAtTheUsdtRate_andThePaidCoinKeepsItsAverage() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        trading.recordDepth(new OrderBookDepth(
                MarketType.SPOT,
                cross,
                TICKS.incrementAndGet(),
                List.of(new PriceLevel(new BigDecimal("0.048"), BigDecimal.ONE)),
                List.of(
                        new PriceLevel(new BigDecimal("0.049"), new BigDecimal("0.4")),
                        new PriceLevel(new BigDecimal("0.051"), new BigDecimal("5"))),
                Instant.now()));

        String id = id(place(trader, limit(cross, "BUY", "0.05", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED")));

        // 0.4 taken at 0.049: 0.0196 QX at 60,000; 0.0004 QX of the lock released, 0.03 still locked.
        assertThat(costBasis(trader, base)).isEqualByComparingTo("1176.00000000");
        assertThat(spot(trader, base)).isEqualByComparingTo("0.3996");
        assertThat(spot(trader, quote)).isEqualByComparingTo("0.0803");
        assertThat(costBasis(trader, quote)).isEqualByComparingTo("4822.82282282");
        assertLedgerInvariant(trader);

        assertThat(matching.fill(resting(trader, id, "0.05"), Instant.now(), FillSource.LIVE))
                .isTrue();

        // The 0.6 left at 0.05: 0.03 QX at 60,000 more.
        assertThat(costBasis(trader, base)).isEqualByComparingTo("2976.00000000");
        assertThat(spot(trader, base)).isEqualByComparingTo("0.999");
        assertThat(spot(trader, quote)).isEqualByComparingTo("0.0503");
        assertThat(costBasis(trader, quote)).isEqualByComparingTo("3021.02102102");
        assertLedgerInvariant(trader);
    }

    /**
     * PT-R19: a replayed fill traded 40 minutes ago is valued at the close of the quote coin's USDT candle of that
     * minute, not at the rate of now.
     */
    @Test
    void PTR19_aReplayedFill_isValuedAtTheQuoteUsdtCloseOfTheMinuteItTraded() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        String id = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(40));
        doReturn(MinuteKlineBatch.of(List.of(
                        closedMinute(quoteUsdt, minute, "57000"),
                        closedMinute(quoteUsdt, minute.plusSeconds(60), "57100"))))
                .when(market)
                .closedMinuteKlines(MarketType.SPOT, quoteUsdt, minute);
        matching.prefetchQuoteRates(MarketType.SPOT, cross, minute, minute.plusSeconds(60));
        clearInvocations(market);

        assertThat(matching.fill(resting(trader, id, "0.04"), minute, FillSource.REPLAY))
                .isTrue();

        verify(market, never()).closedMinuteKlines(any(), any(), any());
        // 0.04 QX at the close of 57,000 of the minute it traded in, not today's 60,000.
        assertThat(costBasis(trader, base)).isEqualByComparingTo("2280.00000000");
        assertThat(lastFill(trader, cross))
                .containsEntry("quote_usdt_price_source", "CANDLE_1M")
                .satisfies(fill ->
                        assertThat((BigDecimal) fill.get("quote_usdt_price")).isEqualByComparingTo("57000"));
        assertLedgerInvariant(trader);
    }

    /**
     * PT-R19, Q-52: when the prefetch of the minute's candle fails or is refused, the prefetch returns all the same and
     * the replayed fill is valued at the rate of now.
     */
    @Test
    void PTR19_aReplayedFillWhoseCandleCannotBeRead_isValuedAtTheRateOfNow() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        String first = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        String second = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        Instant failed = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(40));
        Instant refused = failed.plusSeconds(60);
        doThrow(new IllegalStateException("exchange down"))
                .when(market)
                .closedMinuteKlines(MarketType.SPOT, quoteUsdt, failed);
        doReturn(MinuteKlineBatch.refusedUntil(Instant.now().plusSeconds(60)))
                .when(market)
                .closedMinuteKlines(MarketType.SPOT, quoteUsdt, refused);

        matching.prefetchQuoteRates(MarketType.SPOT, cross, failed, failed);
        matching.prefetchQuoteRates(MarketType.SPOT, cross, refused, refused);
        assertThat(matching.fill(resting(trader, first, "0.04"), failed, FillSource.REPLAY))
                .isTrue();
        assertThat(matching.fill(resting(trader, second, "0.04"), refused, FillSource.REPLAY))
                .isTrue();

        assertThat(costBasis(trader, base)).isEqualByComparingTo("4800.00000000");
        assertThat(lastFill(trader, cross)).containsEntry("quote_usdt_price_source", "LIVE");
        assertLedgerInvariant(trader);
    }

    /**
     * Q-52: a replayed fill never reads the exchange, as it runs under the Trader's lock; without a prefetched close it
     * takes the rate of now.
     */
    @Test
    void Q52_aReplayedFill_makesNoNetworkCall_andWithoutAPrefetchedClose_takesTheRateOfNow() throws Exception {
        UUID trader = trader();
        price(quote + "USDT", "60000");
        price(base + quote, "0.05");
        place(trader, market(quoteUsdt, "BUY", "0.1")).andExpect(status().isCreated());
        String id = id(place(trader, limit(cross, "BUY", "0.04", "1")).andExpect(status().isCreated()));
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(40));
        doReturn(MinuteKlineBatch.of(List.of(closedMinute(quoteUsdt, minute, "57000"))))
                .when(market)
                .closedMinuteKlines(MarketType.SPOT, quoteUsdt, minute);
        clearInvocations(market);

        assertThat(matching.fill(resting(trader, id, "0.04"), minute, FillSource.REPLAY))
                .isTrue();

        verify(market, never()).closedMinuteKlines(any(), any(), any());
        assertThat(costBasis(trader, base)).isEqualByComparingTo("2400.00000000");
        assertThat(lastFill(trader, cross)).containsEntry("quote_usdt_price_source", "LIVE");
        assertLedgerInvariant(trader);
    }

    @Test
    void D96_aPairWhoseQuoteOrBaseHasNoActiveUsdtPair_isRefused_forAnOrderAndAnOrderList() throws Exception {
        UUID trader = trader();
        String orphan = "QY" + quote.substring(2);
        UUID unvaluedQuote = pair(base, orphan, true, "0.000001", "0.001", "0.0001");
        pair(orphan, "USDT", false, "0.01", "0.001", "5");
        String bare = "EY" + quote.substring(2);
        UUID unvaluedBase = pair(bare, quote, true, "0.000001", "0.001", "0.0001");
        price(base + orphan, "0.05");
        price(bare + quote, "0.05");
        price(orphan + "USDT", "1");

        place(trader, market(unvaluedQuote, "BUY", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_PAIR_NOT_VALUED"));
        place(trader, market(unvaluedBase, "BUY", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_PAIR_NOT_VALUED"));
        as(trader, post(PAPER + "/order-lists"), oco(unvaluedQuote))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_PAIR_NOT_VALUED"));

        assertThat(sql.sql("""
                        select count(*) from trade_order o
                         where o.user_id = ?""").param(trader).query(Long.class).single()).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
    }

    // ------------------------------------------------------------------------------------------

    /** A pair of two coins of this test, Spot-enabled or not, with its filters. */
    private UUID pair(String baseCoin, String quoteCoin, boolean spot, String tick, String step, String minNotional) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, is_spot_enabled,
                                                 is_futures_enabled, pair_status, display_order,
                                                 spot_exchange_status, spot_tick_size, spot_step_size,
                                                 spot_min_notional, created_at, updated_at)
                        values (?, ?, ?, ?, ?, false, ?, 0, 'TRADING', ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        coin(baseCoin),
                        coin(quoteCoin),
                        baseCoin + quoteCoin,
                        spot,
                        spot ? "ACTIVE" : "INACTIVE",
                        new BigDecimal(tick),
                        new BigDecimal(step),
                        new BigDecimal(minNotional),
                        now,
                        now)
                .update();
        pairs.add(id);
        return id;
    }

    private UUID coin(String symbol) {
        return sql.sql("select coin_id from coin where symbol = ?")
                .param(symbol)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    Timestamp now = Timestamp.from(Instant.now());
                    sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at)"
                                    + " values (?, ?, ?, ?, ?)")
                            .params(id, symbol, symbol, now, now)
                            .update();
                    coins.add(symbol);
                    return id;
                });
    }

    private static MinuteKline closedMinute(UUID pair, Instant openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(MarketType.SPOT, pair, openTime, price, price, price, true, openTime.plusSeconds(60));
    }

    /** Streams a Spot ticker into the cache, newer than every earlier one. */
    private void price(String symbol, String last) {
        ticker(symbol, last, Instant.now().plusMillis(TICKS.incrementAndGet()));
    }

    private void ticker(String symbol, String last, Instant at) {
        BigDecimal value = new BigDecimal(last);
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        at));
        prices.flush();
    }

    /** Replaces the quote coin's USDT price with one older than the cache serves as current. */
    private void staleQuoteUsdt(Instant at) {
        redis.delete(priceKey(quote + "USDT"));
        ticker(quote + "USDT", "61000", at);
    }

    private static String priceKey(String symbol) {
        return "px:SPOT:" + symbol;
    }

    private String market(UUID pair, String side, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"MARKET\","
                + " \"quantity\": " + quantity + "}";
    }

    private String marketOf(String symbol, String side, String quantity) {
        UUID pair = sql.sql("select pair_id from crypto_pair where symbol = ?")
                .param(symbol)
                .query(UUID.class)
                .single();
        return market(pair, side, quantity);
    }

    private String limit(UUID pair, String side, String price, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"LIMIT\","
                + " \"price\": " + price + ", \"quantity\": " + quantity + "}";
    }

    private String oco(UUID pair) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OCO\", \"oco\": {\"side\": \"BUY\","
                + " \"quantity\": 1, \"takeProfitPrice\": 0.04, \"stopPrice\": 0.06, \"stopLimitPrice\": 0.061}}";
    }

    private RestingOrder resting(UUID trader, String id, String price) {
        return new RestingOrder(
                UUID.fromString(id),
                accountOf(trader),
                MarketType.SPOT,
                cross,
                OrderSide.BUY,
                new BigDecimal(price),
                Instant.now().minusSeconds(1));
    }

    private ResultActions place(UUID trader, String body) throws Exception {
        return as(trader, post(PAPER + "/orders"), body);
    }

    private static String id(ResultActions placed) throws Exception {
        return JsonPath.read(placed.andReturn().getResponse().getContentAsString(), "$.orderId");
    }

    private BigDecimal spotEquity(UUID trader) throws Exception {
        String body = as(trader, get(PAPER + "/equity").param("days", "1"), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new BigDecimal(String.valueOf(JsonPath.<Object>read(body, "$.spotEquity")));
    }

    private Map<String, Object> lastFill(UUID trader, UUID pair) {
        return sql.sql("""
                        select f.quote_usdt_price, f.quote_usdt_price_source
                          from order_fill f
                         where f.user_id = ? and f.pair_id = ?
                         order by f.traded_at desc, f.fill_id desc
                         limit 1""").params(trader, pair).query().singleRow();
    }

    private UUID accountOf(UUID trader) {
        return sql.sql(
                        "select distinct user_id from wallet_ledger_entry where user_id = ? and entry_type = 'INITIAL_GRANT'")
                .param(trader)
                .query(UUID.class)
                .single();
    }

    private BigDecimal spot(UUID trader, String coin) {
        return balanceColumn(trader, coin, "b.free_amount + b.locked_amount");
    }

    private BigDecimal costBasis(UUID trader, String coin) {
        return balanceColumn(trader, coin, "b.cost_basis_amount");
    }

    private BigDecimal averageCost(UUID trader, String coin) {
        return balanceColumn(trader, coin, "round(b.cost_basis_amount / (b.free_amount + b.locked_amount), 12)");
    }

    /** The column of the Trader's Spot balance of the coin; zero when there is no balance, null when it is null. */
    private BigDecimal balanceColumn(UUID trader, String coin, String column) {
        List<BigDecimal> found = new ArrayList<>();
        sql.sql("select " + column + " " + """
                         as found
                          from wallet_balance b
                          join coin c on c.coin_id = b.coin_id
                         where b.user_id = ? and b.wallet_type = 'SPOT' and c.symbol = ?""")
                .params(trader, coin)
                .query()
                .listOfRows()
                .forEach(row -> found.add((BigDecimal) row.get("found")));
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    /** Both parts of the ledger invariant of the P6b review (section 4), for this Trader's account only. */
    private void assertLedgerInvariant(UUID trader) {
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /** A Trader with an opened paper account. */
    private UUID trader() throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@d96.invalid", now, now).update();
        traders.add(id);
        as(id, post(PAPER + "/account"), null).andExpect(status().isCreated());
        return id;
    }

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}

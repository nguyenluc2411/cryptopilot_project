package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.PaperLedgerInvariant;
import com.cryptopilot.paper.service.PaperPnlService;
import com.cryptopilot.paper.service.impl.GrantCosts;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
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
 * A paper account whose coins have no current price: the day's equity snapshot waits until every coin has one, so
 * today's PnL is not known rather than wrong (PT-R02), and the granted coins get their cost once they have one
 * (PT-R13).
 *
 * <p>The granted coins are the real BTC, ETH and BNB, priced through the real cache; the tests remove what they wrote.
 *
 * <p>Rule: TR-13; PT-R02, PT-R13.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperMissingPriceTest {

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
    private PaperPnlService pnl;

    @Autowired
    private GrantCosts grantCosts;

    // The same bean override as PaperCrossPairCostTest, so both share one context.
    @MockitoSpyBean
    private MarketApi market;

    private final List<UUID> traders = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();

    @BeforeEach
    void noExchange() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMinuteKlines(any(), any(), any());
        clearPrices();
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
        for (String coin : GRANTED) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ?").param(coin).update();
            }
        }
        clearPrices();
    }

    /**
     * PT-R02: while a granted coin has no price no snapshot of the day is stored and today's PnL is not known; once
     * every coin has one, the next run stores it, today's PnL counts from it, and a later run stores no second one.
     */
    @Test
    void PTR02_noSnapshotWhileACoinHasNoPrice_thenTheRetryTakesIt_once() throws Exception {
        UUID trader = trader();

        assertThat(snapshots(trader)).isZero();
        equity(trader).andExpect(jsonPath("$.todayPnl").value(nullValue()));
        pnl.snapshotAll();
        assertThat(snapshots(trader)).isZero();

        price("BTCUSDT", "60000");
        price("ETHUSDT", "3000");
        price("BNBUSDT", "500");
        pnl.snapshotAll();

        // 10,000 USDT, 0.1 BTC at 60,000, 1 ETH at 3,000, 10 BNB at 500, and 5,000 USDT in Futures.
        assertThat(snapshots(trader)).isEqualTo(1);
        assertThat(snapshotTotal(trader)).isEqualByComparingTo("29000");
        price("BTCUSDT", "61000");
        equity(trader)
                .andExpect(jsonPath("$.totalEquity").value(29100.0))
                .andExpect(jsonPath("$.todayPnl").value(100.0))
                .andExpect(jsonPath("$.history.length()").value(1));

        pnl.snapshotAll();

        assertThat(snapshots(trader)).isEqualTo(1);
        assertThat(snapshotTotal(trader)).isEqualByComparingTo("29000");
    }

    /**
     * PT-R13: granted coins without a price at the opening have no cost; once a price is known the backfill gives each
     * its amount at that price, once, and leaves a coin a fill has changed as it is.
     */
    @Test
    void PTR13_grantedCoinsWithoutAPrice_areValuedOnceTheyHaveOne_unlessTheyTraded() throws Exception {
        UUID trader = trader();
        assertThat(costBasis(trader, "BTC")).isNull();
        assertThat(costBasis(trader, "ETH")).isNull();
        assertThat(costBasis(trader, "BNB")).isNull();
        UUID btcUsdt = btcUsdt();
        price("BTCUSDT", "60000");
        as(
                        trader,
                        post(PAPER + "/orders"),
                        "{\"pairId\": \"" + btcUsdt + "\", \"market\": \"SPOT\", \"side\": \"BUY\","
                                + " \"type\": \"MARKET\", \"quantity\": 0.01}")
                .andExpect(status().isCreated());
        price("ETHUSDT", "3000");

        grantCosts.valueAll();

        assertThat(costBasis(trader, "BTC")).as("a coin a fill changed").isNull();
        assertThat(costBasis(trader, "ETH")).isEqualByComparingTo("3000");
        assertThat(costBasis(trader, "BNB")).as("a coin still without a price").isNull();

        price("BNBUSDT", "500");
        price("ETHUSDT", "3500");
        grantCosts.valueAll();

        assertThat(costBasis(trader, "BNB")).isEqualByComparingTo("5000");
        assertThat(costBasis(trader, "ETH")).as("valued once").isEqualByComparingTo("3000");
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /** PT-R13: without a live price at the opening, a granted coin is valued at its recent closed 1-minute candle. */
    @Test
    void PTR13_atTheOpening_aGrantedCoinWithoutALivePrice_isValuedAtItsRecentMinuteClose() throws Exception {
        UUID btcUsdt = btcUsdt();
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(3));
        trading.recordClosedMinute(new MinuteKline(
                MarketType.SPOT,
                btcUsdt,
                minute,
                new BigDecimal("58500"),
                new BigDecimal("59500"),
                new BigDecimal("59000"),
                true,
                minute.plusSeconds(60)));

        UUID trader = trader();

        assertThat(costBasis(trader, "BTC")).isEqualByComparingTo("5900");
    }

    // ------------------------------------------------------------------------------------------

    /** A Spot BTCUSDT pair with a step of 0.00001. */
    private UUID btcUsdt() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, is_spot_enabled,
                                                 is_futures_enabled, pair_status, display_order,
                                                 spot_exchange_status, spot_tick_size, spot_step_size,
                                                 spot_min_notional, created_at, updated_at)
                        values (?, ?, ?, 'BTCUSDT', true, false, 'ACTIVE', 0, 'TRADING', 0.01, 0.00001, 5, ?, ?)""").params(id, coin("BTC"), coin("USDT"), now, now).update();
        pairs.add(id);
        return id;
    }

    private UUID coin(String symbol) {
        return market.ensureCoins(List.of(symbol)).get(0).coinId();
    }

    /** Streams a Spot ticker into the cache, newer than every earlier one. */
    private void price(String symbol, String last) {
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
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
        prices.flush();
    }

    private void clearPrices() {
        Set<String> keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private ResultActions equity(UUID trader) throws Exception {
        return as(trader, get(PAPER + "/equity").param("days", "7"), null).andExpect(status().isOk());
    }

    private long snapshots(UUID trader) {
        return sql.sql("""
                        select count(*) from equity_snapshot s
                         where s.user_id = ?""").param(trader).query(Long.class).single();
    }

    private BigDecimal snapshotTotal(UUID trader) {
        return sql.sql("""
                        select s.total_equity_amount from equity_snapshot s
                         where s.user_id = ?""").param(trader).query(BigDecimal.class).single();
    }

    private UUID accountOf(UUID trader) {
        return sql.sql(
                        "select distinct user_id from wallet_ledger_entry where user_id = ? and entry_type = 'INITIAL_GRANT'")
                .param(trader)
                .query(UUID.class)
                .single();
    }

    /** The Trader's Spot cost of the coin; null when not known. */
    private BigDecimal costBasis(UUID trader, String coin) {
        List<Map<String, Object>> found =
                sql.sql("""
                        select b.cost_basis_amount as cost
                          from wallet_balance b
                          join coin c on c.coin_id = b.coin_id
                         where b.user_id = ? and b.wallet_type = 'SPOT' and c.symbol = ?""").params(trader, coin).query().listOfRows();
        assertThat(found).hasSize(1);
        return (BigDecimal) found.get(0).get("cost");
    }

    /** A Trader with an opened paper account. */
    private UUID trader() throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@ptf2.invalid", now, now).update();
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

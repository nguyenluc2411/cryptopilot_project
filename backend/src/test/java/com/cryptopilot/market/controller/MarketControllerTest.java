package com.cryptopilot.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The market data API of UC-09 end to end, against the migrated schema: which pairs are listed and how they page,
 * which candles come back and how a client pages through them, how the snapshot blocks and the sparse metric series
 * of D-45 are answered — a row with metrics only included — and which requests are refused, with which message.
 *
 * <p>The application's clock is the real one, so the data is written relative to the moment the test runs.
 *
 * <p>Rule: UC-09, BR-07, BR-08, BR-10, BR-11; NSF-03, NSF-04; SRS 3.1.3, 3.3.1, 3.3.3; D-45, D-46.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketControllerTest {

    private static final String BASE = "/api/v1/market";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    private MarketTestData data;
    private Instant minute;

    @BeforeEach
    void setUp() {
        minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        data = new MarketTestData(sql, minute);
    }

    @AfterEach
    void clear() {
        sql.sql("delete from funding_rate_history").update();
        data.clear();
    }

    // ------------------------------------------------------------------ pairs

    /** BR-07: only pairs enabled on the market are listed, lowest display order first, then by symbol. */
    @Test
    void BR07_pairs_listOnlyThePairsEnabledOnTheMarket_inDisplayOrder() throws Exception {
        data.pair("ETHUSDT", true, true, "TRADING", "TRADING", 2);
        data.pair("BTCUSDT", true, false, "TRADING", null, 1);
        data.pair("SOLUSDT", false, true, null, "TRADING", 0);
        data.pair("XRPUSDT", false, false, "TRADING", "TRADING", 0);

        mvc.perform(get(BASE + "/pairs").param("market", "spot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[0].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.items[0].market").value("SPOT"))
                .andExpect(jsonPath("$.items[0].baseAsset").value("BTC"))
                .andExpect(jsonPath("$.items[0].quoteAsset").value("USDT"))
                .andExpect(jsonPath("$.items[0].displayOrder").value(1))
                .andExpect(jsonPath("$.items[0].tickSize").doesNotExist())
                .andExpect(jsonPath("$.items[1].symbol").value("ETHUSDT"));
        mvc.perform(get(BASE + "/pairs").param("market", "FUTURES"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].symbol").value("SOLUSDT"))
                .andExpect(jsonPath("$.items[1].symbol").value("ETHUSDT"));
    }

    /** Pages are stable: walked one after another they list every pair once, with nothing repeated or left out. */
    @Test
    void UC09_pairs_pageStablyWithoutRepeatsOrGaps() throws Exception {
        List<String> expected = new ArrayList<>();
        for (String symbol : List.of("AAAUSDT", "BBBUSDT", "CCCUSDT", "DDDUSDT", "EEEUSDT")) {
            data.pair(symbol, true, false, "TRADING", null, 5);
            expected.add(symbol);
        }

        List<String> seen = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            String body = mvc.perform(get(BASE + "/pairs")
                            .param("market", "spot")
                            .param("page", String.valueOf(page))
                            .param("pageSize", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(5))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].symbol"));
        }

        assertThat(seen).as("same display order: by symbol").containsExactlyElementsOf(expected);
        mvc.perform(get(BASE + "/pairs")
                        .param("market", "spot")
                        .param("page", "4")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(5));
    }

    /** An unknown market or a page out of range is MSG01. */
    @Test
    void UC09_pairs_anUnknownMarketOrAPageOutOfRange_isMsg01() throws Exception {
        assertMsg01(mvc.perform(get(BASE + "/pairs").param("market", "bond")));
        assertMsg01(mvc.perform(get(BASE + "/pairs").param("market", "spot").param("page", "0")));
        assertMsg01(mvc.perform(get(BASE + "/pairs").param("market", "spot").param("pageSize", "0")));
        assertMsg01(mvc.perform(get(BASE + "/pairs").param("market", "spot").param("pageSize", "101")));
    }

    // ------------------------------------------------------------------ candles

    /** BR-08: the latest closed candles, oldest first, at most the limit, every decimal as a string. */
    @Test
    void BR08_candles_returnTheLatestClosedCandlesOldestFirst() throws Exception {
        UUID btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);
        List<Instant> opens = hourlyCandles(btc, "SPOT", 5);

        mvc.perform(get(BASE + "/spot/btcusdt/candles").param("tf", "1h").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.market").value("SPOT"))
                .andExpect(jsonPath("$.timeframe").value("1h"))
                .andExpect(jsonPath("$.candles.length()").value(3))
                .andExpect(jsonPath("$.candles[0].openTime").value(opens.get(2).toString()))
                .andExpect(jsonPath("$.candles[2].openTime").value(opens.get(4).toString()))
                .andExpect(jsonPath("$.candles[2].close").value("104.500000000000"))
                .andExpect(jsonPath("$.candles[2].tradeCount").value(44));
        mvc.perform(get(BASE + "/futures/BTCUSDT/candles").param("tf", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candles").isEmpty());
    }

    /** Paging backwards with {@code to} = the first open time held returns every candle exactly once. */
    @Test
    void UC09_candles_pageBackwardsWithoutRepeatsOrGaps() throws Exception {
        UUID btc = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        List<Instant> opens = hourlyCandles(btc, "SPOT", 5);

        List<String> seen = new ArrayList<>();
        String to = null;
        for (int call = 0; call < 3; call++) {
            var request = get(BASE + "/spot/BTCUSDT/candles").param("tf", "1h").param("limit", "2");
            if (to != null) {
                request.param("to", to);
            }
            String body = mvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<String> page = JsonPath.read(body, "$.candles[*].openTime");
            seen.addAll(0, page);
            to = page.isEmpty() ? to : page.get(0);
        }

        assertThat(seen)
                .containsExactlyElementsOf(opens.stream().map(Instant::toString).toList());
    }

    /** A range with {@code from} returns the candles opened in it. */
    @Test
    void UC09_candles_aRangeReturnsTheCandlesOpenedInIt() throws Exception {
        UUID btc = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        List<Instant> opens = hourlyCandles(btc, "SPOT", 5);

        mvc.perform(get(BASE + "/spot/BTCUSDT/candles")
                        .param("tf", "1h")
                        .param("from", opens.get(1).toString())
                        .param("to", opens.get(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candles.length()").value(2))
                .andExpect(jsonPath("$.candles[0].openTime").value(opens.get(1).toString()))
                .andExpect(jsonPath("$.candles[1].openTime").value(opens.get(2).toString()));
    }

    /** BR-08: only 15m, 1h, 4h and 1d are served; anything else is MSG01. */
    @ParameterizedTest
    @ValueSource(strings = {"1m", "5m", "1w", "H1", ""})
    void BR08_candles_anUnstoredTimeframe_isMsg01(String timeframe) throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        assertMsg01(mvc.perform(get(BASE + "/spot/BTCUSDT/candles").param("tf", timeframe)));
    }

    /** Ranges that end in the future, are empty or backwards, or hold too many candles, and limits out of range. */
    @Test
    void UC09_candles_invalidRangesAndLimits_areMsg01() throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        String path = BASE + "/spot/BTCUSDT/candles";
        Instant earlier = minute.minus(Duration.ofHours(2));

        assertMsg01(mvc.perform(
                get(path).param("tf", "1h").param("from", minute.toString()).param("to", earlier.toString())));
        assertMsg01(mvc.perform(
                get(path).param("tf", "1h").param("from", earlier.toString()).param("to", earlier.toString())));
        assertMsg01(mvc.perform(get(path)
                .param("tf", "1h")
                .param("to", minute.plus(Duration.ofHours(1)).toString())));
        assertMsg01(mvc.perform(get(path)
                .param("tf", "1h")
                .param("from", minute.minus(Duration.ofHours(1001)).toString())));
        assertMsg01(mvc.perform(get(path).param("tf", "1h").param("limit", "0")));
        assertMsg01(mvc.perform(get(path).param("tf", "1h").param("limit", "1001")));
        mvc.perform(get(path)
                        .param("tf", "1h")
                        .param("from", minute.minus(Duration.ofHours(1000)).toString())
                        .param("to", minute.toString()))
                .andExpect(status().isOk());
    }

    /** BR-07: a symbol that is unknown, disabled, or enabled only on the other market is MSG41, all alike. */
    @Test
    void BR07_candles_aPairNotEnabledOnTheMarket_isMsg41() throws Exception {
        data.pair("ETHUSDT", false, false, "TRADING", "TRADING", 0);
        data.pair("SOLUSDT", false, true, null, "TRADING", 0);

        assertMsg41(mvc.perform(get(BASE + "/spot/NOPEUSDT/candles").param("tf", "1h")));
        assertMsg41(mvc.perform(get(BASE + "/spot/ETHUSDT/candles").param("tf", "1h")));
        assertMsg41(mvc.perform(get(BASE + "/spot/SOLUSDT/candles").param("tf", "1h")));
        assertMsg01(mvc.perform(get(BASE + "/bonds/SOLUSDT/candles").param("tf", "1h")));
    }

    // ------------------------------------------------------------------ stats

    /** The latest Spot ticker, with the minute it was stored for and the server's instant. */
    @Test
    void UC09_spotStats_showTheLatestTickerWithItsInstant() throws Exception {
        UUID btc = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        spotRow(btc, minute.minus(Duration.ofMinutes(2)), "63000.1");
        spotRow(btc, minute.minus(Duration.ofMinutes(1)), "63050.25");

        mvc.perform(get(BASE + "/spot/BTCUSDT/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.serverTime").exists())
                .andExpect(jsonPath("$.ticker.asOf")
                        .value(minute.minus(Duration.ofMinutes(1)).toString()))
                .andExpect(jsonPath("$.ticker.lastPrice").value("63050.250000000000"));
    }

    /** Nothing stored yet: the ticker is absent, not zero. */
    @Test
    void UC09_spotStats_withNothingStored_leaveTheTickerAbsent() throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        mvc.perform(get(BASE + "/spot/BTCUSDT/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").doesNotExist());
        assertMsg41(mvc.perform(get(BASE + "/futures/BTCUSDT/stats")));
    }

    /**
     * D-45: each futures block comes from the row that holds it, with that row's instant — the price from the latest
     * minute with a mark price, the metrics from the latest 5-minute reading, the settlement from the funding history.
     */
    @Test
    void D45_futuresStats_takeEachBlockFromTheRowThatHoldsIt() throws Exception {
        UUID btc = data.pair("BTCUSDT", false, true, null, "TRADING", 0);
        Instant priceMinute = minute.minus(Duration.ofMinutes(3));
        Instant metricsMinute = minute.minus(Duration.ofMinutes(2));
        priceRow(btc, priceMinute, "63055.5");
        metricsRow(btc, metricsMinute, "95800.386", "1.2346");
        settlement(btc, minute.minus(Duration.ofHours(2)), "0.00001422");

        mvc.perform(get(BASE + "/futures/BTCUSDT/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.asOf").value(priceMinute.toString()))
                .andExpect(jsonPath("$.price.markPrice").value("63055.500000000000"))
                .andExpect(jsonPath("$.price.fundingRate").value("0.00010000"))
                .andExpect(jsonPath("$.openInterest.time").value(metricsMinute.toString()))
                .andExpect(jsonPath("$.openInterest.openInterest").value("95800.386000000000"))
                .andExpect(jsonPath("$.longShortRatio.time").value(metricsMinute.toString()))
                .andExpect(jsonPath("$.longShortRatio.longShortRatio").value("1.23460000"))
                .andExpect(jsonPath("$.lastFundingSettlement.fundingTime")
                        .value(minute.minus(Duration.ofHours(2)).toString()))
                .andExpect(jsonPath("$.lastFundingSettlement.fundingRate").value("0.00001422"));
    }

    /** NSF-04 created a row before any price reached it: the metrics are answered, the price is absent, not faked. */
    @Test
    void NSF04_futuresStats_aRowWithMetricsOnly_isNeverReadAsAPrice() throws Exception {
        UUID btc = data.pair("BTCUSDT", false, true, null, "TRADING", 0);
        metricsRow(btc, minute.minus(Duration.ofMinutes(5)), "95800.386", "1.2346");

        mvc.perform(get(BASE + "/futures/BTCUSDT/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").doesNotExist())
                .andExpect(jsonPath("$.openInterest.openInterest").value("95800.386000000000"))
                .andExpect(jsonPath("$.lastFundingSettlement").doesNotExist());
    }

    // ------------------------------------------------------------------ metrics

    /**
     * D-45: over ten minute rows with a price each, open interest was read at two instants and the ratio at one; the
     * series hold exactly those points — the minutes between are not filled, interpolated or set to zero.
     */
    @Test
    void D45_metrics_listOnlyTheInstantsThatHoldAValue() throws Exception {
        UUID btc = data.pair("BTCUSDT", false, true, null, "TRADING", 0);
        Instant start = minute.minus(Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) {
            priceRow(btc, start.plus(Duration.ofMinutes(i)), "63000");
        }
        openInterest(btc, start, "100.5");
        openInterest(btc, start.plus(Duration.ofMinutes(5)), "101.5");
        ratio(btc, start.plus(Duration.ofMinutes(5)), "1.1");

        mvc.perform(get(BASE + "/futures/BTCUSDT/metrics")
                        .param("from", start.toString())
                        .param("to", minute.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(start.toString()))
                .andExpect(jsonPath("$.to").value(minute.toString()))
                .andExpect(jsonPath("$.openInterest.length()").value(2))
                .andExpect(jsonPath("$.openInterest[0].time").value(start.toString()))
                .andExpect(jsonPath("$.openInterest[1].time")
                        .value(start.plus(Duration.ofMinutes(5)).toString()))
                .andExpect(jsonPath("$.openInterest[1].openInterest").value("101.500000000000"))
                .andExpect(jsonPath("$.longShortRatio.length()").value(1))
                .andExpect(jsonPath("$.longShortRatio[0].longShortRatio").value("1.10000000"))
                .andExpect(jsonPath("$.fundingSettlements").isEmpty());
    }

    /**
     * BR-10: the earliest stored instant of each series is stated, even when it lies before the range, so the chart
     * can say which period is really available; settled funding in the range is listed at its normalized instant.
     */
    @Test
    void BR10_metrics_stateTheEarliestStoredInstant_andListSettlementsInTheRange() throws Exception {
        UUID btc = data.pair("BTCUSDT", false, true, null, "TRADING", 0);
        Instant old = minute.minus(Duration.ofDays(3));
        openInterest(btc, old, "90");
        openInterest(btc, minute.minus(Duration.ofHours(1)), "100");
        settlement(btc, minute.minus(Duration.ofDays(2)), "0.0001");
        settlement(btc, minute.minus(Duration.ofHours(4)), "0.0002");

        mvc.perform(get(BASE + "/futures/BTCUSDT/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openInterest.length()").value(1))
                .andExpect(jsonPath("$.fundingSettlements.length()").value(1))
                .andExpect(jsonPath("$.fundingSettlements[0].fundingTime")
                        .value(minute.minus(Duration.ofHours(4)).toString()))
                .andExpect(jsonPath("$.fundingSettlements[0].fundingRate").value("0.00020000"))
                .andExpect(jsonPath("$.availableFrom.openInterest").value(old.toString()))
                .andExpect(jsonPath("$.availableFrom.fundingSettlements")
                        .value(minute.minus(Duration.ofDays(2)).toString()))
                .andExpect(jsonPath("$.availableFrom.longShortRatio").doesNotExist());
    }

    /** Ranges that end in the future, are empty or backwards, or are longer than seven days are MSG01. */
    @Test
    void UC09_metrics_invalidRanges_areMsg01() throws Exception {
        data.pair("BTCUSDT", false, true, null, "TRADING", 0);
        String path = BASE + "/futures/BTCUSDT/metrics";
        Instant earlier = minute.minus(Duration.ofHours(1));

        assertMsg01(mvc.perform(get(path).param("from", minute.toString()).param("to", earlier.toString())));
        assertMsg01(mvc.perform(get(path).param("from", earlier.toString()).param("to", earlier.toString())));
        assertMsg01(mvc.perform(
                get(path).param("to", minute.plus(Duration.ofMinutes(10)).toString())));
        assertMsg01(mvc.perform(
                get(path).param("from", minute.minus(Duration.ofDays(8)).toString())));
        mvc.perform(get(path)
                        .param("from", minute.minus(Duration.ofDays(7)).toString())
                        .param("to", minute.toString()))
                .andExpect(status().isOk());
        assertMsg41(mvc.perform(get(BASE + "/futures/NOPEUSDT/metrics")));
    }

    // ------------------------------------------------------------------ access

    /** SRS 3.1.3: the market data is readable without a token, and only readable: a write needs a session and has none. */
    @Test
    void SRS313_marketData_isPublicForReadingOnly() throws Exception {
        data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);

        mvc.perform(get(BASE + "/pairs").param("market", "spot")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/futures/BTCUSDT/metrics")).andExpect(status().isOk());
        mvc.perform(post(BASE + "/pairs")).andExpect(status().isUnauthorized());
        mvc.perform(put(BASE + "/spot/BTCUSDT/stats")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE + "/futures/BTCUSDT/metrics")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private static void assertMsg01(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    private static void assertMsg41(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    /** Hourly closed candles ending before the current hour; their open times, oldest first. */
    private List<Instant> hourlyCandles(UUID pairId, String market, int count) {
        Instant currentHour = minute.truncatedTo(ChronoUnit.HOURS);
        List<Instant> opens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant open = currentHour.minus(Duration.ofHours(count - i));
            opens.add(open);
            BigDecimal close = new BigDecimal("100.5").add(BigDecimal.valueOf(i));
            sql.sql("""
                            insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price,
                                               high_price, low_price, close_price, base_volume, quote_volume, trade_count)
                            values (?, ?, '1h', ?, ?, 100, 110, 90, ?, 10, 1000, ?)""")
                    .params(
                            pairId,
                            market,
                            Timestamp.from(open),
                            Timestamp.from(open.plus(Duration.ofHours(1)).minusMillis(1)),
                            close,
                            40 + i)
                    .update();
        }
        return opens;
    }

    private void spotRow(UUID pairId, Instant at, String lastPrice) {
        sql.sql("insert into spot_market_data (pair_id, snapshot_time, last_price) values (?, ?, ?)")
                .params(pairId, Timestamp.from(at), new BigDecimal(lastPrice))
                .update();
    }

    private void priceRow(UUID pairId, Instant at, String markPrice) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, mark_price, index_price, funding_rate,
                                                         next_funding_time)
                        values (?, ?, ?, ?, 0.0001, ?)""")
                .params(
                        pairId,
                        Timestamp.from(at),
                        new BigDecimal(markPrice),
                        new BigDecimal(markPrice),
                        Timestamp.from(at.plus(Duration.ofHours(8))))
                .update();
    }

    /** A row NSF-04 created: open interest and ratio, no price. */
    private void metricsRow(UUID pairId, Instant at, String openInterest, String ratio) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, open_interest, open_interest_value,
                                                         long_short_ratio, long_account_ratio, short_account_ratio)
                        values (?, ?, ?, 1000, ?, 0.55, 0.45)""")
                .params(pairId, Timestamp.from(at), new BigDecimal(openInterest), new BigDecimal(ratio))
                .update();
    }

    private void openInterest(UUID pairId, Instant at, String value) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, open_interest, open_interest_value)
                        values (?, ?, ?, 1000)
                        on conflict (pair_id, snapshot_time) do update
                           set open_interest = excluded.open_interest, open_interest_value = excluded.open_interest_value""").params(pairId, Timestamp.from(at), new BigDecimal(value)).update();
    }

    private void ratio(UUID pairId, Instant at, String value) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, long_short_ratio, long_account_ratio,
                                                         short_account_ratio)
                        values (?, ?, ?, 0.5, 0.5)
                        on conflict (pair_id, snapshot_time) do update set long_short_ratio = excluded.long_short_ratio,
                           long_account_ratio = excluded.long_account_ratio, short_account_ratio = excluded.short_account_ratio""").params(pairId, Timestamp.from(at), new BigDecimal(value)).update();
    }

    private void settlement(UUID pairId, Instant at, String rate) {
        sql.sql(
                        "insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price) values (?, ?, ?, 63000)")
                .params(pairId, Timestamp.from(at), new BigDecimal(rate))
                .update();
    }
}

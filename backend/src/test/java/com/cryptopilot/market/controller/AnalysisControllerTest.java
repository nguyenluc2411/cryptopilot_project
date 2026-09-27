package com.cryptopilot.market.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.UserRole;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The analysis API of UC-10 and UC-11 end to end, against the migrated schema: what a stored row answers, which
 * timeframe the setup score is read on, the dominant side beside a Futures score and its absence on Spot, the nulls of
 * a series that is still warming up or has nothing stored, and the refusals.
 *
 * <p>Rule: UC-10, UC-11, BR-07, BR-08, BR-12, BR-13, BR-14; SRS 3.3.2; TECHNICAL_DESIGN 7.4; D-53.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AnalysisControllerTest {

    private static final String BASE = "/api/v1/analysis";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private TechnicalIndicatorRepository indicators;

    @Autowired
    private JwtEncoder jwtEncoder;

    private MarketTestData data;
    private Instant hour;

    @BeforeEach
    void setUp() {
        hour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        data = new MarketTestData(sql, hour);
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    // ------------------------------------------------------------------ Spot

    /**
     * A full Spot row: the indicators and levels as stored, the four components, and the DAY_TRADING v1 score read on
     * 1h — 35·75 + 30·50 + 15·40 + 20·60 = 5925, so 59.25, rounded to 59. No derivatives, no dominant side.
     */
    @Test
    void UC10_spotRow_answersTheStoredValuesAndTheDayTradingScore() throws Exception {
        UUID pair = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        store(pair, MarketType.SPOT, "1h", hour, full(), spot("75", "50", "40", "60"));

        analysis("spot", "btcusdt", "1h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.market").value("SPOT"))
                .andExpect(jsonPath("$.timeframe").value("1h"))
                .andExpect(jsonPath("$.openTime").value(hour.toString()))
                .andExpect(jsonPath("$.indicators.ema200").value("90.0000000000"))
                .andExpect(jsonPath("$.indicators.rsi14").value("55.5000000000"))
                .andExpect(jsonPath("$.nearestSupport").value("95.0000000000"))
                .andExpect(jsonPath("$.nearestResistance").value("120.0000000000"))
                .andExpect(jsonPath("$.components.trend").value("75.00"))
                .andExpect(jsonPath("$.components.formulaVersion").value("v1"))
                .andExpect(jsonPath("$.components", not(hasKey("derivatives"))))
                .andExpect(jsonPath("$.setupScore.score").value(59))
                .andExpect(jsonPath("$.setupScore.preset").value("DAY_TRADING"))
                .andExpect(jsonPath("$.setupScore.presetVersion").value("v1"))
                .andExpect(jsonPath("$.setupScore.timeframe").value("1h"))
                .andExpect(jsonPath("$.setupScore.openTime").value(hour.toString()))
                .andExpect(jsonPath("$.setupScore", not(hasKey("dominantSide"))))
                .andExpect(jsonPath("$.setupScore.components", not(hasKey("derivatives"))));
    }

    /**
     * D-53: the chart's timeframe and the preset's are independent. Asking for 4h answers the 4h row, and the score
     * is still read on DAY_TRADING's 1h components, which the response names.
     */
    @Test
    void BR13_anotherTimeframe_answersItsRow_andTheScoreOfThePresetTimeframe() throws Exception {
        UUID pair = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        Instant fourHours = hour.truncatedTo(ChronoUnit.DAYS);
        store(pair, MarketType.SPOT, "4h", fourHours, full(), spot("100", "100", "100", "100"));
        store(pair, MarketType.SPOT, "1h", hour, full(), spot("0", "0", "0", "0"));

        analysis("spot", "BTCUSDT", "4h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeframe").value("4h"))
                .andExpect(jsonPath("$.openTime").value(fourHours.toString()))
                .andExpect(jsonPath("$.components.trend").value("100.00"))
                .andExpect(jsonPath("$.setupScore.timeframe").value("1h"))
                .andExpect(jsonPath("$.setupScore.openTime").value(hour.toString()))
                .andExpect(jsonPath("$.setupScore.score").value(0));
    }

    /** SRS 3.3.2: the chart's default timeframe is 1h. */
    @Test
    void UC10_withoutATimeframe_answersTheHourlyRow() throws Exception {
        UUID pair = data.pair("BTCUSDT", true, false, "TRADING", null, 0);
        store(pair, MarketType.SPOT, "1h", hour, full(), spot("75", "50", "40", "60"));

        mvc.perform(get(BASE + "/spot/BTCUSDT").header(HttpHeaders.AUTHORIZATION, trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeframe").value("1h"))
                .andExpect(jsonPath("$.openTime").value(hour.toString()));
    }

    // ------------------------------------------------------------------ Futures

    /**
     * Futures: five components, DAY_TRADING v1 weights 30·100 + 25·100 + 15·50 + 15·100 + 15·50 = 8500, so 85, and the
     * dominant side read from the stored EMAs and the close of the same candle in {@code ohlcv}.
     */
    @ParameterizedTest(name = "close {0}, EMA20 {1}, EMA50 {2}, EMA200 {3}: {4}")
    @CsvSource({
        "110, 105, 100, 90, LONG",
        "90, 95, 100, 110, SHORT",
        "100, 100, 100, 100, NEUTRAL",
    })
    void UC11_futuresRow_carriesTheDominantSideBesideTheScore(
            String close, String ema20, String ema50, String ema200, String side) throws Exception {
        UUID pair = data.pair("ETHUSDT", false, true, null, "TRADING", 0);
        store(pair, MarketType.FUTURES, "1h", hour, emas(ema20, ema50, ema200), futures());
        candle(pair, MarketType.FUTURES, hour, close);

        analysis("futures", "ETHUSDT", "1h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("FUTURES"))
                .andExpect(jsonPath("$.components.derivatives").value("50.00"))
                .andExpect(jsonPath("$.setupScore.score").value(85))
                .andExpect(jsonPath("$.setupScore.components.derivatives").value("50.00"))
                .andExpect(jsonPath("$.setupScore.dominantSide").value(side));
    }

    /** Without the close of the scored candle the side cannot be read, so it is null — the score is still given. */
    @Test
    void UC11_withoutTheCandleClose_theDominantSideIsNull() throws Exception {
        UUID pair = data.pair("ETHUSDT", false, true, null, "TRADING", 0);
        store(pair, MarketType.FUTURES, "1h", hour, emas("105", "100", "90"), futures());

        analysis("futures", "ETHUSDT", "1h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.score").value(85))
                .andExpect(jsonPath("$.setupScore", hasKey("dominantSide")))
                .andExpect(jsonPath("$.setupScore.dominantSide").value(nullValue()));
    }

    // ------------------------------------------------------------------ insufficient data

    /**
     * SRS 3.3.2: under 200 closed candles EMA200, the trend and so the score cannot be computed. They are answered as
     * null — present and empty — never as zero or a partial sum; the dominant side needs EMA200 too.
     */
    @Test
    void SRS332_aSeriesStillWarmingUp_answersNulls_neverAValue() throws Exception {
        UUID pair = data.pair("ETHUSDT", false, true, null, "TRADING", 0);
        store(pair, MarketType.FUTURES, "1h", hour, emas("105", "100", null), futuresWithoutTrend());
        candle(pair, MarketType.FUTURES, hour, "110");

        analysis("futures", "ETHUSDT", "1h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators", hasKey("ema200")))
                .andExpect(jsonPath("$.indicators.ema200").value(nullValue()))
                .andExpect(jsonPath("$.indicators.ema20").value("105.0000000000"))
                .andExpect(jsonPath("$.components", hasKey("trend")))
                .andExpect(jsonPath("$.components.trend").value(nullValue()))
                .andExpect(jsonPath("$.setupScore", hasKey("score")))
                .andExpect(jsonPath("$.setupScore.score").value(nullValue()))
                .andExpect(jsonPath("$.setupScore.dominantSide").value(nullValue()));
    }

    /** Nothing stored for the series: every value null, the preset still named, no dominant side on Spot. */
    @Test
    void SRS332_nothingStored_answersNulls_andNamesThePreset() throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        analysis("spot", "BTCUSDT", "15m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeframe").value("15m"))
                .andExpect(jsonPath("$.openTime").value(nullValue()))
                .andExpect(jsonPath("$.indicators.sma20").value(nullValue()))
                .andExpect(jsonPath("$.nearestSupport").value(nullValue()))
                .andExpect(jsonPath("$.components.formulaVersion").value(nullValue()))
                .andExpect(jsonPath("$.setupScore.score").value(nullValue()))
                .andExpect(jsonPath("$.setupScore.preset").value("DAY_TRADING"))
                .andExpect(jsonPath("$.setupScore.timeframe").value("1h"))
                .andExpect(jsonPath("$.setupScore", not(hasKey("dominantSide"))));
    }

    /** The same on Futures: the dominant side is there, and null. */
    @Test
    void SRS332_nothingStoredOnFutures_answersANullDominantSide() throws Exception {
        data.pair("ETHUSDT", false, true, null, "TRADING", 0);

        analysis("futures", "ETHUSDT", "1h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components", hasKey("derivatives")))
                .andExpect(jsonPath("$.setupScore.score").value(nullValue()))
                .andExpect(jsonPath("$.setupScore", hasKey("dominantSide")))
                .andExpect(jsonPath("$.setupScore.dominantSide").value(nullValue()));
    }

    // ------------------------------------------------------------------ refusals

    /** BR-08: only 15m, 1h, 4h and 1d are analysed; anything else is MSG01. */
    @ParameterizedTest
    @ValueSource(strings = {"1m", "5m", "1w", "H1", ""})
    void BR08_anUnstoredTimeframe_isMsg01(String timeframe) throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        analysis("spot", "BTCUSDT", timeframe)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    /** An unknown market is MSG01. */
    @Test
    void UC10_anUnknownMarket_isMsg01() throws Exception {
        analysis("margin", "BTCUSDT", "1h")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    /** BR-07: a symbol that is unknown, disabled, or enabled only on the other market is MSG41, all alike. */
    @Test
    void BR07_aPairNotEnabledOnTheMarket_isMsg41() throws Exception {
        data.pair("ETHUSDT", false, false, "TRADING", "TRADING", 0);
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        for (String[] request : new String[][] {{"spot", "ETHUSDT"}, {"futures", "BTCUSDT"}, {"spot", "NOPEUSDT"}}) {
            analysis(request[0], request[1], "1h")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.messageCode").value("MSG41"));
        }
    }

    /** SRS 3.1.3: the analysis is not public. */
    @Test
    void SRS313_aGuest_isRefused() throws Exception {
        data.pair("BTCUSDT", true, false, "TRADING", null, 0);

        mvc.perform(get(BASE + "/spot/BTCUSDT").param("tf", "1h")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ fixtures

    private ResultActions analysis(String market, String symbol, String timeframe) throws Exception {
        return mvc.perform(get(BASE + "/" + market + "/" + symbol)
                .param("tf", timeframe)
                .header(HttpHeaders.AUTHORIZATION, trader()));
    }

    /** The indicators of a warmed-up series with an up-trend: close above EMA20 above EMA50 above EMA200. */
    private static String[] full() {
        return new String[] {"100", "105", "100", "90", "55.5"};
    }

    /** EMAs as given (EMA200 {@code null} while warming up), the rest of a warmed-up series. */
    private static String[] emas(String ema20, String ema50, String ema200) {
        return new String[] {"100", ema20, ema50, ema200, "55.5"};
    }

    private static ComponentScores spot(String trend, String momentum, String volume, String level) {
        return new ComponentScores(
                MarketType.SPOT, "1h", "v1", score(trend), score(momentum), score(volume), score(level), null);
    }

    private static ComponentScores futures() {
        return new ComponentScores(
                MarketType.FUTURES, "1h", "v1", score("100"), score("100"), score("50"), score("100"), score("50"));
    }

    private static ComponentScores futuresWithoutTrend() {
        return new ComponentScores(
                MarketType.FUTURES, "1h", "v1", null, score("100"), score("50"), score("100"), score("50"));
    }

    private static BigDecimal score(String value) {
        return new BigDecimal(value).setScale(2);
    }

    /**
     * Stores the row of one candle; {@code values} are SMA20, EMA20, EMA50, EMA200, RSI14. The components are stored
     * on {@code timeframe}, whatever the fixture was built with.
     */
    private void store(
            UUID pair, MarketType market, String timeframe, Instant openTime, String[] values, ComponentScores c) {
        ComponentScores onTimeframe = new ComponentScores(
                market, timeframe, c.formulaVersion(), c.trend(), c.momentum(), c.volume(), c.level(), c.derivatives());
        indicators.upsert(new StoredIndicators(
                pair,
                openTime,
                decimal(values[0]),
                decimal(values[1]),
                decimal(values[2]),
                decimal(values[3]),
                decimal(values[4]),
                decimal("0.5"),
                decimal("0.3"),
                decimal("0.2"),
                decimal("110"),
                decimal("100"),
                decimal("90"),
                decimal("12"),
                decimal("95"),
                decimal("120"),
                onTimeframe,
                openTime.plusSeconds(3605)));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value).setScale(10);
    }

    /** The closed hourly candle opened at {@code openTime}, closing at {@code close}. */
    private void candle(UUID pair, MarketType market, Instant openTime, String close) {
        sql.sql("""
                        insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price,
                                           high_price, low_price, close_price, base_volume, quote_volume, trade_count)
                        values (?, ?, '1h', ?, ?, 100, 120, 80, ?, 10, 1000, 5)""")
                .params(
                        pair,
                        market.name(),
                        Timestamp.from(openTime),
                        Timestamp.from(openTime.plusSeconds(3599)),
                        new BigDecimal(close))
                .update();
    }

    private String trader() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(UUID.randomUUID().toString())
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

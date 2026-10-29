package com.cryptopilot.market.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.FixedClockConfig;
import com.cryptopilot.support.PlanTraders;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The access classes of D-60 on every market and analysis endpoint, for a Guest and for a Trader on each plan tier:
 * the raw market data and the current futures price are public; the Spot analysis is any Trader's, with the component
 * scores locked unless the plan includes {@code SCORE_COMPONENTS} (CR-08); the Futures analysis, the futures metrics
 * and the open interest, long/short and settlement blocks of the futures stats need {@code FUTURES_ANALYSIS} (MSG29).
 *
 * <p>Rule: BR-62, UC-09, UC-10, UC-11, UC-53; SRS 3.1.3, 3.3.1, 3.3.3, CR-08; D-60.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanGatesApiTest {

    /** Who calls: a visitor without a token, or a Trader on one tier. */
    enum Caller {
        GUEST(null),
        FREE(PlanTier.FREE),
        PRO(PlanTier.PRO),
        PREMIUM(PlanTier.PREMIUM);

        private final PlanTier tier;

        Caller(PlanTier tier) {
            this.tier = tier;
        }

        boolean paid() {
            return tier == PlanTier.PRO || tier == PlanTier.PREMIUM;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private MarketTestData data;
    private PlanTraders traders;
    private Instant minute;

    @BeforeEach
    void setUp() {
        minute = FixedClockConfig.NOW.truncatedTo(ChronoUnit.MINUTES);
        data = new MarketTestData(sql, minute);
        traders = new PlanTraders(sql, jwtEncoder, FixedClockConfig.NOW);
        UUID btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, mark_price, index_price, funding_rate,
                                                         next_funding_time, open_interest, open_interest_value,
                                                         long_short_ratio, long_account_ratio, short_account_ratio)
                        values (?, ?, 63000, 63000, 0.0001, ?, 95800, 1000, 1.2, 0.55, 0.45)""")
                .params(
                        btc,
                        Timestamp.from(minute.minus(Duration.ofMinutes(5))),
                        Timestamp.from(minute.plus(Duration.ofHours(8))))
                .update();
        sql.sql("insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price)"
                        + " values (?, ?, ?, 63000)")
                .params(btc, Timestamp.from(minute.minus(Duration.ofHours(2))), new BigDecimal("0.0001"))
                .update();
    }

    @AfterEach
    void clear() {
        sql.sql("delete from funding_rate_history").update();
        data.clear();
        traders.clear();
    }

    /** D-60: pairs, candles of both markets and the Spot ticker are public on every plan. */
    @ParameterizedTest
    @EnumSource(Caller.class)
    void D60_rawMarketData_isReadableByEveryCaller(Caller caller) throws Exception {
        perform(get("/api/v1/market/pairs").param("market", "futures"), caller).andExpect(status().isOk());
        perform(get("/api/v1/market/spot/BTCUSDT/candles").param("tf", "1h"), caller)
                .andExpect(status().isOk());
        perform(get("/api/v1/market/futures/BTCUSDT/candles").param("tf", "1h"), caller)
                .andExpect(status().isOk());
        perform(get("/api/v1/market/spot/BTCUSDT/stats"), caller).andExpect(status().isOk());
    }

    /**
     * SRS 3.3.1 and D-60: everybody reads the mark price, index price, funding rate and next funding time; the open
     * interest, long/short ratio and last settlement are answered only on a plan with {@code FUTURES_ANALYSIS}.
     */
    @ParameterizedTest
    @EnumSource(Caller.class)
    void BR62_futuresStats_answerTheMetricBlocksOnlyWithFuturesAnalysis(Caller caller) throws Exception {
        ResultActions result = perform(get("/api/v1/market/futures/BTCUSDT/stats"), caller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.markPrice").exists())
                .andExpect(jsonPath("$.price.indexPrice").exists())
                .andExpect(jsonPath("$.price.fundingRate").exists())
                .andExpect(jsonPath("$.price.nextFundingTime").exists());
        if (caller.paid()) {
            result.andExpect(jsonPath("$.openInterest.openInterest").exists())
                    .andExpect(jsonPath("$.longShortRatio.longShortRatio").exists())
                    .andExpect(jsonPath("$.lastFundingSettlement.fundingRate").exists());
        } else {
            result.andExpect(jsonPath("$.openInterest").value(nullValue()))
                    .andExpect(jsonPath("$.longShortRatio").value(nullValue()))
                    .andExpect(jsonPath("$.lastFundingSettlement").value(nullValue()));
        }
    }

    /** D-60: the futures metrics need a Trader (MSG44) whose plan includes {@code FUTURES_ANALYSIS} (MSG29). */
    @ParameterizedTest
    @EnumSource(Caller.class)
    void BR62_futuresMetrics_needFuturesAnalysis(Caller caller) throws Exception {
        ResultActions result = perform(get("/api/v1/market/futures/BTCUSDT/metrics"), caller);
        switch (caller) {
            case GUEST ->
                result.andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.messageCode").value("MSG44"));
            case FREE ->
                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.messageCode").value("MSG29"));
            default ->
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.openInterest.length()").value(1))
                        .andExpect(jsonPath("$.fundingSettlements.length()").value(1));
        }
    }

    /** UC-10 and CR-08: the Spot analysis is any Trader's; FREE reads it with both component blocks locked. */
    @ParameterizedTest
    @EnumSource(Caller.class)
    void BR62_spotAnalysis_locksTheComponentsWithoutScoreComponents(Caller caller) throws Exception {
        ResultActions result = perform(get("/api/v1/analysis/spot/BTCUSDT"), caller);
        switch (caller) {
            case GUEST -> result.andExpect(status().isUnauthorized());
            case FREE ->
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.componentsLocked").value(true))
                        .andExpect(jsonPath("$.components").value(nullValue()))
                        .andExpect(jsonPath("$.setupScore", hasKey("score")))
                        .andExpect(jsonPath("$.setupScore.preset").value("DAY_TRADING"))
                        .andExpect(jsonPath("$.setupScore.components").value(nullValue()));
            default ->
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.componentsLocked").value(false))
                        .andExpect(jsonPath("$.components", hasKey("trend")))
                        .andExpect(jsonPath("$.setupScore.components", hasKey("trend")));
        }
    }

    /** UC-11 and D-60: the Futures analysis needs {@code FUTURES_ANALYSIS}; FREE is refused with MSG29. */
    @ParameterizedTest
    @EnumSource(Caller.class)
    void BR62_futuresAnalysis_needsFuturesAnalysis(Caller caller) throws Exception {
        ResultActions result = perform(get("/api/v1/analysis/futures/BTCUSDT"), caller);
        switch (caller) {
            case GUEST -> result.andExpect(status().isUnauthorized());
            case FREE ->
                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.messageCode").value("MSG29"));
            default ->
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.componentsLocked").value(false))
                        .andExpect(jsonPath("$.components", hasKey("derivatives")))
                        .andExpect(jsonPath("$.setupScore", hasKey("dominantSide")));
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Caller caller) throws Exception {
        if (caller.tier != null) {
            request.header(HttpHeaders.AUTHORIZATION, traders.bearer(caller.tier));
        }
        return mvc.perform(request);
    }
}

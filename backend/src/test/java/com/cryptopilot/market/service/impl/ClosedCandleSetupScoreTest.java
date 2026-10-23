package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.support.FixedClockConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/**
 * The setup score {@link MarketApi} gives other modules, against the migrated schema and the configured presets: for
 * one stored candle it equals the score the analysis API answers a Trader of the same preset, on Spot and on Futures;
 * a missing component gives a {@code null} score; neither a row of the candle still forming nor one of an older candle is read.
 *
 * <p>The application's clock is fixed ({@link FixedClockConfig}). The read runs outside a caller's transaction, so the
 * rows are committed and removed after each test.
 *
 * <p>Rule: BR-13, BR-70; TECHNICAL_DESIGN 7.4; D-53, D-112; Q-74.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ClosedCandleSetupScoreTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private TechnicalIndicatorRepository indicators;

    @Autowired
    private MarketApi market;

    private final List<UUID> traders = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void enablePair() {
        symbol = "SS" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, FixedClockConfig.NOW).pair(symbol, true, true, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            sql.sql("delete from user_profile where user_id = ?").param(trader).update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        new MarketTestData(sql, FixedClockConfig.NOW).remove(pair);
    }

    /** The trading style of the profile, the preset it selects (BR-13), and that preset's timeframe. */
    @ParameterizedTest(name = "{0} on {3}")
    @CsvSource({
        "SCALPING, SCALPING,    15m, SPOT",
        "DAY,      DAY_TRADING, 1h,  SPOT",
        "SWING,    SWING,       4h,  SPOT",
        "SCALPING, SCALPING,    15m, FUTURES",
        "DAY,      DAY_TRADING, 1h,  FUTURES",
        "SWING,    SWING,       4h,  FUTURES"
    })
    void BR13_aKnownCandle_givesTheScoreOfTheAnalysisApi_forEachPreset(
            String tradingStyle, SetupStyle preset, String timeframe, MarketType type) throws Exception {
        Instant closed = latestClosedOpen(timeframe);
        store(type, timeframe, closed, "75", "50", "40", "60", "30");
        UUID trader = trader(tradingStyle);

        String body = mvc.perform(get("/api/v1/analysis/" + type.name().toLowerCase() + "/" + symbol)
                        .param("tf", timeframe)
                        .header(HttpHeaders.AUTHORIZATION, bearer(trader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.preset").value(preset.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Integer analysed = JsonPath.read(body, "$.setupScore.score");

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, type, timeframe, preset, null, FixedClockConfig.NOW);

        assertThat(analysed).isNotNull();
        assertThat(reading.score()).isEqualTo(analysed);
        assertThat(reading.preset()).isEqualTo(preset.name() + " v1");
        assertThat(reading.openTime()).isEqualTo(closed);
        assertThat(reading.openTime().toString()).isEqualTo(JsonPath.read(body, "$.setupScore.openTime"));
    }

    /** DAY_TRADING v1 on 1h: 35·75 + 30·50 + 15·40 + 20·60 = 5925, so 59 (TECHNICAL_DESIGN 7.4). */
    @Test
    void BR13_withoutAPreset_readsDayTradingAtItsCurrentVersion() {
        store(MarketType.SPOT, "1h", latestClosedOpen("1h"), "75", "50", "40", "60", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, "1h", null, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isEqualTo(59);
        assertThat(reading.preset()).isEqualTo("DAY_TRADING v1");
    }

    @Test
    void BR70_aMissingComponent_givesANullScore_andStillNamesTheCandle() {
        Instant closed = latestClosedOpen("1h");
        store(MarketType.SPOT, "1h", closed, null, "50", "40", "60", null);

        SetupScoreReading reading = market.closedCandleSetupScore(
                pair, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isEqualTo(closed);
    }

    @Test
    void BR70_aFuturesRowWithoutDerivatives_givesANullScore() {
        store(MarketType.FUTURES, "1h", latestClosedOpen("1h"), "75", "50", "40", "60", null);

        assertThat(market.closedCandleSetupScore(
                                pair, MarketType.FUTURES, "1h", SetupStyle.DAY_TRADING, null, FixedClockConfig.NOW)
                        .score())
                .isNull();
    }

    @Test
    void BR70_nothingStored_givesANullScore_forTheLatestClosedCandle() {
        SetupScoreReading reading = market.closedCandleSetupScore(
                pair, MarketType.SPOT, "4h", SetupStyle.SWING, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isEqualTo(latestClosedOpen("4h"));
        assertThat(reading.preset()).isEqualTo("SWING v1");
    }

    /** D-112: the latest closed candle's own row gives the score. */
    @ParameterizedTest
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void BR70_theLatestClosedCandle_givesAScore(SetupStyle preset, String timeframe) {
        store(MarketType.SPOT, timeframe, latestClosedOpen(timeframe), "60", "60", "60", "60", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, timeframe, preset, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isEqualTo(60);
        assertThat(reading.openTime()).isEqualTo(latestClosedOpen(timeframe));
    }

    /** D-112: when the newest row is one interval older than the latest closed candle, its score is stale. */
    @ParameterizedTest
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void BR70_aRowOneIntervalOlder_givesANullScore(SetupStyle preset, String timeframe) {
        Instant older = latestClosedOpen(timeframe).minus(duration(timeframe));
        store(MarketType.SPOT, timeframe, older, "60", "60", "60", "60", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, timeframe, preset, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isEqualTo(latestClosedOpen(timeframe));
    }

    /**
     * Q-77: the preset's v1 weights apply to the components of a timeframe it does not read on the analysis screen.
     * DAY_TRADING Spot: 35·75 + 30·50 + 15·40 + 20·60 = 5925 → 59; SWING Spot: 40·75 + 20·50 + 10·40 + 30·60 = 6200 →
     * 62; SCALPING Spot: 20·75 + 40·50 + 25·40 + 15·60 = 5400 → 54.
     */
    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
        "DAY_TRADING, 4h,  59",
        "DAY_TRADING, 1d,  59",
        "SWING,       1h,  62",
        "SWING,       1d,  62",
        "SCALPING,    4h,  54"
    })
    void BR70_aTimeframeOtherThanThePresets_isWeightedWithThePreset(SetupStyle preset, String timeframe, int score) {
        store(MarketType.SPOT, timeframe, latestClosedOpen(timeframe), "75", "50", "40", "60", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, timeframe, preset, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isEqualTo(score);
        assertThat(reading.preset()).isEqualTo(preset.name() + " v1");
        assertThat(reading.timeframe()).isEqualTo(timeframe);
    }

    /** After an outage of the indicator job only rows of a day ago are stored: none of them is read. */
    @Test
    void BR70_afterAnOutage_withOnlyOldRows_givesANullScore() {
        Instant closed = latestClosedOpen("1h");
        for (int hoursBefore = 24; hoursBefore <= 30; hoursBefore++) {
            store(MarketType.SPOT, "1h", closed.minus(Duration.ofHours(hoursBefore)), "60", "60", "60", "60", null);
        }

        SetupScoreReading reading = market.closedCandleSetupScore(
                pair, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null, FixedClockConfig.NOW);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isEqualTo(closed);
    }

    /** D-112: the candle still forming is never read, even when a row for it exists. */
    @ParameterizedTest
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void BR70_theFormingCandle_isIgnored(SetupStyle preset, String timeframe) {
        Instant closed = latestClosedOpen(timeframe);
        Instant forming = closed.plus(duration(timeframe));
        store(MarketType.SPOT, timeframe, closed, "0", "0", "0", "0", null);
        store(MarketType.SPOT, timeframe, forming, "100", "100", "100", "100", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, timeframe, preset, null, FixedClockConfig.NOW);

        assertThat(reading.openTime()).isEqualTo(closed);
        assertThat(reading.score()).isZero();
    }

    /** The open time of the latest candle of {@code timeframe} that has closed by the fixed clock. */
    private static Instant latestClosedOpen(String timeframe) {
        long seconds = duration(timeframe).toSeconds();
        long now = FixedClockConfig.NOW.getEpochSecond();
        return Instant.ofEpochSecond(now - now % seconds).minusSeconds(seconds);
    }

    private static Duration duration(String timeframe) {
        return switch (timeframe) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "4h" -> Duration.ofHours(4);
            default -> Duration.ofDays(1);
        };
    }

    private void store(
            MarketType type,
            String timeframe,
            Instant openTime,
            String trend,
            String momentum,
            String volume,
            String level,
            String derivatives) {
        ComponentScores c = new ComponentScores(
                type,
                timeframe,
                "v1",
                score(trend),
                score(momentum),
                score(volume),
                score(level),
                type == MarketType.FUTURES ? score(derivatives) : null);
        indicators.upsert(new StoredIndicators(
                pair,
                openTime,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                c,
                openTime.plus(duration(timeframe))));
    }

    private static BigDecimal score(String value) {
        return value == null ? null : new BigDecimal(value).setScale(2);
    }

    private UUID trader(String tradingStyle) {
        UUID id = UUID.randomUUID();
        traders.add(id);
        Timestamp now = Timestamp.from(FixedClockConfig.NOW);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@q74.invalid", now, now).update();
        sql.sql("""
                        insert into user_profile (user_id, display_name, trading_style, created_at, updated_at)
                        values (?, 'Trader', ?, ?, ?)""").params(id, tradingStyle, now, now).update();
        return id;
    }

    private String bearer(UUID userId) {
        Instant now = FixedClockConfig.NOW;
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

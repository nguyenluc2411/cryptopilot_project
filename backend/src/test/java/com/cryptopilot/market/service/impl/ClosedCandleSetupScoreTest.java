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
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The setup score {@link MarketApi} gives other modules, against the migrated schema and the configured presets: for
 * one stored candle it equals the score the analysis API answers a Trader of the same preset, on Spot and on Futures;
 * a missing component gives a {@code null} score; a row of the candle still forming is never read.
 *
 * <p>The application's clock is fixed ({@link FixedClockConfig}). Every test rolls back.
 *
 * <p>Rule: BR-13, BR-70; TECHNICAL_DESIGN 7.4; D-53, D-112; Q-74.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FixedClockConfig.class})
@Transactional
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

    private UUID pair;

    @BeforeEach
    void enablePair() {
        pair = new MarketTestData(sql, FixedClockConfig.NOW).pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);
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

        String body = mvc.perform(get("/api/v1/analysis/" + type.name().toLowerCase() + "/BTCUSDT")
                        .param("tf", timeframe)
                        .header(HttpHeaders.AUTHORIZATION, bearer(trader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.preset").value(preset.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Integer analysed = JsonPath.read(body, "$.setupScore.score");

        SetupScoreReading reading = market.closedCandleSetupScore(pair, type, timeframe, preset, null);

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

        SetupScoreReading reading = market.closedCandleSetupScore(pair, MarketType.SPOT, "1h", null, null);

        assertThat(reading.score()).isEqualTo(59);
        assertThat(reading.preset()).isEqualTo("DAY_TRADING v1");
    }

    @Test
    void BR70_aMissingComponent_givesANullScore_andStillNamesTheCandle() {
        Instant closed = latestClosedOpen("1h");
        store(MarketType.SPOT, "1h", closed, null, "50", "40", "60", null);

        SetupScoreReading reading =
                market.closedCandleSetupScore(pair, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isEqualTo(closed);
    }

    @Test
    void BR70_aFuturesRowWithoutDerivatives_givesANullScore() {
        store(MarketType.FUTURES, "1h", latestClosedOpen("1h"), "75", "50", "40", "60", null);

        assertThat(market.closedCandleSetupScore(pair, MarketType.FUTURES, "1h", SetupStyle.DAY_TRADING, null)
                        .score())
                .isNull();
    }

    @Test
    void BR70_nothingStored_givesANullScoreAndNoCandle() {
        SetupScoreReading reading = market.closedCandleSetupScore(pair, MarketType.SPOT, "4h", SetupStyle.SWING, null);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isNull();
        assertThat(reading.preset()).isEqualTo("SWING v1");
    }

    /** D-112: the candle still forming is never read, even when a row for it exists. */
    @ParameterizedTest
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void BR70_theFormingCandle_isIgnored(SetupStyle preset, String timeframe) {
        Instant closed = latestClosedOpen(timeframe);
        Instant forming = closed.plus(duration(timeframe));
        store(MarketType.SPOT, timeframe, closed, "0", "0", "0", "0", null);
        store(MarketType.SPOT, timeframe, forming, "100", "100", "100", "100", null);

        SetupScoreReading reading = market.closedCandleSetupScore(pair, MarketType.SPOT, timeframe, preset, null);

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

package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.config.SetupScoreProperties;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.dto.response.SpotSetupScoreResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which preset a setup score is read under (D-53): the one the caller's profile selects through the style source,
 * and DAY_TRADING when there is no caller, no source, or no selection. The score is read on that preset's timeframe.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
class AnalysisServiceImplTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant OPEN = Instant.parse("2026-09-27T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-27T10:30:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MarketRequests requests = mock(MarketRequests.class);
    private final TechnicalIndicatorRepository indicators = mock(TechnicalIndicatorRepository.class);
    private final OhlcvRepository candles = mock(OhlcvRepository.class);
    private final SetupStyleSource source = mock(SetupStyleSource.class);

    @BeforeEach
    void setUp() {
        CryptoPair pair = mock(CryptoPair.class);
        when(pair.getId()).thenReturn(PAIR);
        when(pair.getSymbol()).thenReturn("BTCUSDT");
        when(requests.enabledPair(any(), anyString())).thenReturn(pair);
        for (String tf : new String[] {"15m", "1h", "4h"}) {
            when(indicators.latest(PAIR, MarketType.SPOT, tf)).thenReturn(Optional.of(row(tf)));
        }
    }

    @Test
    void BR13_withoutAStyleSource_readsDayTradingOnOneHour() {
        SpotSetupScoreResponse score = score(service(null), USER);

        assertThat(score.preset()).isEqualTo(SetupStyle.DAY_TRADING);
        assertThat(score.timeframe()).isEqualTo("1h");
    }

    @Test
    void BR13_withoutACaller_readsDayTrading_andDoesNotAskTheSource() {
        SpotSetupScoreResponse score = score(service(source), null);

        assertThat(score.preset()).isEqualTo(SetupStyle.DAY_TRADING);
        verify(source, never()).styleOf(any());
    }

    @Test
    void BR13_aProfileWithoutAStyle_readsDayTrading() {
        when(source.styleOf(USER)).thenReturn(Optional.empty());

        assertThat(score(service(source), USER).preset()).isEqualTo(SetupStyle.DAY_TRADING);
    }

    @Test
    void BR13_theCallersStyle_selectsThePreset_andItsTimeframe() {
        when(source.styleOf(USER)).thenReturn(Optional.of(SetupStyle.SWING));

        SpotSetupScoreResponse score = score(service(source), USER);

        assertThat(score.preset()).isEqualTo(SetupStyle.SWING);
        assertThat(score.presetVersion()).isEqualTo("v1");
        assertThat(score.timeframe()).isEqualTo("4h");
        assertThat(score.components().trend()).isEqualByComparingTo("40");
        assertThat(score.score()).isEqualTo(40);
    }

    // ------------------------------------------------------------------ closed candle setup score (D-112, Q-74)

    /** The latest candle whose close is not after now: an hourly read asks for rows opened by 09:30:15. */
    @Test
    void BR70_theRead_asksForACandleClosedByNow() {
        Instant closedBy = NOW.minus(Duration.ofHours(1));
        when(indicators.latestOpenedBy(PAIR, MarketType.SPOT, "1h", closedBy)).thenReturn(Optional.of(row("1h")));

        SetupScoreReading reading =
                service(null).closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null);

        assertThat(reading.score()).isEqualTo(10);
        assertThat(reading.preset()).isEqualTo("DAY_TRADING v1");
        assertThat(reading.timeframe()).isEqualTo("1h");
        assertThat(reading.openTime()).isEqualTo(OPEN);
        verify(indicators, never()).latest(any(), any(), anyString());
    }

    @Test
    void BR13_withoutAStyle_readsTheDefaultPreset_notTheSource() {
        when(indicators.latestOpenedBy(any(), any(), anyString(), any())).thenReturn(Optional.of(row("1h")));

        SetupScoreReading reading = service(source).closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", null, null);

        assertThat(reading.style()).isEqualTo(SetupStyle.DAY_TRADING);
        verify(source, never()).styleOf(any());
    }

    /** Only the configured version's weights are held; another version cannot be weighted again. */
    @Test
    void BR70_aPresetVersionNoLongerConfigured_givesANullScore() {
        when(indicators.latestOpenedBy(any(), any(), anyString(), any())).thenReturn(Optional.of(row("1h")));

        SetupScoreReading reading =
                service(null).closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, "v0");

        assertThat(reading.score()).isNull();
        assertThat(reading.preset()).isEqualTo("DAY_TRADING v0");
        assertThat(reading.openTime()).isEqualTo(OPEN);
    }

    /** A preset's weights are defined for its own timeframe only (TECHNICAL_DESIGN 7.4); Q-77 asks what else to do. */
    @Test
    void BR70_aTimeframeOtherThanThePresets_givesANullScore() {
        when(indicators.latestOpenedBy(any(), any(), anyString(), any())).thenReturn(Optional.of(row("4h")));

        SetupScoreReading reading =
                service(null).closedCandleSetupScore(PAIR, MarketType.SPOT, "4h", SetupStyle.DAY_TRADING, null);

        assertThat(reading.score()).isNull();
        assertThat(reading.timeframe()).isEqualTo("4h");
    }

    @Test
    void BR70_noClosedCandle_givesANullScoreAndNoOpenTime() {
        when(indicators.latestOpenedBy(any(), any(), anyString(), any())).thenReturn(Optional.empty());

        SetupScoreReading reading =
                service(null).closedCandleSetupScore(PAIR, MarketType.SPOT, "15m", SetupStyle.SCALPING, null);

        assertThat(reading.score()).isNull();
        assertThat(reading.openTime()).isNull();
    }

    @Test
    void BR08_aTimeframeOutsideBr08_isAProgrammingError() {
        AnalysisServiceImpl service = service(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.closedCandleSetupScore(PAIR, MarketType.SPOT, "5m", null, null));
    }

    private static SpotSetupScoreResponse score(AnalysisServiceImpl service, UUID caller) {
        AnalysisResponse response = service.analysis("spot", "BTCUSDT", "1h", caller);
        return (SpotSetupScoreResponse) response.setupScore();
    }

    @SuppressWarnings("unchecked")
    private AnalysisServiceImpl service(SetupStyleSource styles) {
        ObjectProvider<SetupStyleSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(styles);
        return new AnalysisServiceImpl(requests, indicators, candles, presets(), provider, CLOCK);
    }

    /** The v1 presets; only the timeframes and versions matter here. */
    private static SetupScoreProperties presets() {
        ComponentWeights spot = new ComponentWeights(25, 25, 25, 25, null);
        ComponentWeights futures = new ComponentWeights(20, 20, 20, 20, 20);
        return new SetupScoreProperties(Map.of(
                SetupStyle.SCALPING, new SetupScoreProperties.Preset("v1", "15m", spot, futures),
                SetupStyle.DAY_TRADING, new SetupScoreProperties.Preset("v1", "1h", spot, futures),
                SetupStyle.SWING, new SetupScoreProperties.Preset("v1", "4h", spot, futures)));
    }

    /** A row whose four components all equal a number told apart by timeframe: 15m → 15, 1h → 10, 4h → 40. */
    private static StoredIndicators row(String tf) {
        BigDecimal v = new BigDecimal(
                switch (tf) {
                    case "15m" -> "15";
                    case "4h" -> "40";
                    default -> "10";
                });
        ComponentScores c = new ComponentScores(MarketType.SPOT, tf, "v1", v, v, v, v, null);
        return new StoredIndicators(
                PAIR, OPEN, null, null, null, null, null, null, null, null, null, null, null, null, null, null, c,
                OPEN);
    }
}

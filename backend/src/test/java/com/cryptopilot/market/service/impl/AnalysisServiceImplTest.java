package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.config.SetupScoreProperties;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.dto.response.SpotSetupScoreResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import java.math.BigDecimal;
import java.time.Instant;
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

    private static SpotSetupScoreResponse score(AnalysisServiceImpl service, UUID caller) {
        AnalysisResponse response = service.analysis("spot", "BTCUSDT", "1h", caller);
        return (SpotSetupScoreResponse) response.setupScore();
    }

    @SuppressWarnings("unchecked")
    private AnalysisServiceImpl service(SetupStyleSource styles) {
        ObjectProvider<SetupStyleSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(styles);
        return new AnalysisServiceImpl(requests, indicators, candles, presets(), provider);
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

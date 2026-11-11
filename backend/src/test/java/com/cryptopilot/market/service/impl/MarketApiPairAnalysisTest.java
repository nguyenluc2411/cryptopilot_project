package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.dto.response.ComponentScoresResponse;
import com.cryptopilot.market.dto.response.FuturesComponentsResponse;
import com.cryptopilot.market.dto.response.FuturesSetupScoreResponse;
import com.cryptopilot.market.dto.response.IndicatorsResponse;
import com.cryptopilot.market.dto.response.SetupScoreResponse;
import com.cryptopilot.market.dto.response.SpotComponentsResponse;
import com.cryptopilot.market.dto.response.SpotSetupScoreResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.FuturesMetricsRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.repository.MarketDataQueryRepository;
import com.cryptopilot.market.service.AnalysisService;
import com.cryptopilot.market.service.MinuteKlineService;
import com.cryptopilot.market.service.PriceCacheService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code MarketApi.pairAnalysis} (T-074): the analysis of SCR-10 / SCR-11 mapped for the AI context, on Spot and on
 * Futures, with parts missing, and nothing for a pair that is off on the market.
 */
class MarketApiPairAnalysisTest {

    private final CryptoPairRepository pairs = mock(CryptoPairRepository.class);
    private final PriceCacheService prices = mock(PriceCacheService.class);
    private final AnalysisService analysis = mock(AnalysisService.class);
    private final MarketApiImpl api = new MarketApiImpl(
            pairs,
            mock(CoinRepository.class),
            mock(LeverageBracketRepository.class),
            prices,
            mock(MinuteKlineService.class),
            mock(CoinWriter.class),
            mock(TradingMarketData.class),
            mock(FuturesMetricsRepository.class),
            mock(MarketDataQueryRepository.class),
            analysis,
            Clock.systemUTC());
    private final UUID pairId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private void pair(MarketType market, boolean enabled) {
        CryptoPair pair = mock(CryptoPair.class);
        when(pair.isEnabledOn(market)).thenReturn(enabled);
        when(pair.getSymbol()).thenReturn("BTCUSDT");
        when(pairs.findById(pairId)).thenReturn(Optional.of(pair));
    }

    private static AnalysisResponse response(Object indicators, Object components, Object setupScore) {
        AnalysisResponse response = mock(AnalysisResponse.class);
        when(response.symbol()).thenReturn("BTCUSDT");
        when(response.timeframe()).thenReturn("1h");
        when(response.openTime()).thenReturn(Instant.parse("2026-11-07T09:00:00Z"));
        when(response.indicators()).thenReturn((IndicatorsResponse) indicators);
        when(response.components()).thenReturn((ComponentScoresResponse) components);
        when(response.setupScore()).thenReturn((SetupScoreResponse) setupScore);
        return response;
    }

    private static PriceLookup found(String last, String mark, String funding) {
        return new PriceLookup(
                PriceLookup.Status.FOUND,
                Optional.of(new CachedPrice(
                        MarketType.FUTURES,
                        "BTCUSDT",
                        last == null ? null : new BigDecimal(last),
                        null,
                        null,
                        mark == null ? null : new BigDecimal(mark),
                        null,
                        funding == null ? null : new BigDecimal(funding),
                        null,
                        Instant.now())));
    }

    @Test
    void T074_aPairOffOnTheMarket_hasNoAnalysis() {
        pair(MarketType.SPOT, false);

        assertThat(api.pairAnalysis(pairId, MarketType.SPOT, "1h", userId)).isEmpty();
        verifyNoInteractions(analysis);
    }

    @Test
    void T074_aSpotAnalysis_hasNoMarkPriceFundingOrSide_andMissingIndicatorsReadAsNone() {
        pair(MarketType.SPOT, true);
        SpotComponentsResponse components = mock(SpotComponentsResponse.class);
        when(components.trend()).thenReturn(new BigDecimal("75"));
        SpotSetupScoreResponse score = mock(SpotSetupScoreResponse.class);
        when(score.score()).thenReturn(58);
        when(score.preset()).thenReturn(SetupStyle.DAY_TRADING);
        AnalysisResponse response = response(null, components, score);
        when(analysis.analysis("SPOT", "BTCUSDT", "1h", userId)).thenReturn(response);
        when(prices.latestLast(MarketType.SPOT, "BTCUSDT")).thenReturn(PriceLookup.missing());

        PairAnalysis result =
                api.pairAnalysis(pairId, MarketType.SPOT, "1h", userId).orElseThrow();

        assertThat(result.setupScore()).isEqualTo(58);
        assertThat(result.preset()).isEqualTo(SetupStyle.DAY_TRADING);
        assertThat(result.components().trend()).isEqualByComparingTo("75");
        assertThat(result.components().derivatives()).isNull();
        assertThat(result.indicators().rsi14()).isNull();
        assertThat(result.lastPrice()).isNull();
        assertThat(result.dominantSide()).isNull();
        assertThat(result.markPrice()).isNull();
        assertThat(result.fundingRate()).isNull();
    }

    @Test
    void T074_aFuturesAnalysis_carriesTheSideTheMarkPriceAndTheFundingRate() {
        pair(MarketType.FUTURES, true);
        IndicatorsResponse indicators = mock(IndicatorsResponse.class);
        when(indicators.rsi14()).thenReturn(new BigDecimal("42.8"));
        FuturesComponentsResponse components = mock(FuturesComponentsResponse.class);
        when(components.derivatives()).thenReturn(new BigDecimal("60"));
        FuturesSetupScoreResponse score = mock(FuturesSetupScoreResponse.class);
        when(score.score()).thenReturn(64);
        when(score.preset()).thenReturn(SetupStyle.SWING);
        when(score.dominantSide()).thenReturn(DominantSide.SHORT);
        AnalysisResponse response = response(indicators, components, score);
        when(analysis.analysis("FUTURES", "BTCUSDT", "4h", userId)).thenReturn(response);
        when(prices.latestLast(MarketType.FUTURES, "BTCUSDT")).thenReturn(found("75000", null, null));
        when(prices.latest(MarketType.FUTURES, "BTCUSDT")).thenReturn(found("75000", "75010", "0.0001"));

        PairAnalysis result =
                api.pairAnalysis(pairId, MarketType.FUTURES, "4h", userId).orElseThrow();

        assertThat(result.setupScore()).isEqualTo(64);
        assertThat(result.preset()).isEqualTo(SetupStyle.SWING);
        assertThat(result.dominantSide()).isEqualTo(DominantSide.SHORT);
        assertThat(result.components().derivatives()).isEqualByComparingTo("60");
        assertThat(result.indicators().rsi14()).isEqualByComparingTo("42.8");
        assertThat(result.lastPrice()).isEqualByComparingTo("75000");
        assertThat(result.markPrice()).isEqualByComparingTo("75010");
        assertThat(result.fundingRate()).isEqualByComparingTo("0.0001");
    }

    @Test
    void T074_anAnalysisWithoutScoreOrComponents_mapsThemToNothing() {
        pair(MarketType.SPOT, true);
        AnalysisResponse response = response(null, null, null);
        when(analysis.analysis("SPOT", "BTCUSDT", "1h", userId)).thenReturn(response);
        when(prices.latestLast(MarketType.SPOT, "BTCUSDT")).thenReturn(PriceLookup.missing());

        PairAnalysis result =
                api.pairAnalysis(pairId, MarketType.SPOT, "1h", userId).orElseThrow();

        assertThat(result.setupScore()).isNull();
        assertThat(result.preset()).isNull();
        assertThat(result.components()).isNull();
    }
}

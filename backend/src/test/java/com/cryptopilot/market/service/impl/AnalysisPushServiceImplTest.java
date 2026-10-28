package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.dto.response.AnalysisComponentsUpdateResponse;
import com.cryptopilot.market.dto.response.AnalysisUpdateResponse;
import com.cryptopilot.market.dto.response.FuturesComponentsResponse;
import com.cryptopilot.market.dto.response.SpotComponentsResponse;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.enums.MarketType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/**
 * The stored row of each closed candle goes out once on the analysis topic of its series, without any score, and once
 * on its components topic; a candle already pushed, or an older one, is not pushed again, and a broker failure stays
 * here.
 *
 * <p>Rule: NSF-05; TECHNICAL_DESIGN 9; D-126.
 */
class AnalysisPushServiceImplTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant OPEN = Instant.parse("2026-10-28T09:00:00Z");
    private static final String MAIN = "/topic/analysis.SPOT.BTCUSDT.1h";

    private final SimpMessageSendingOperations broker = mock(SimpMessageSendingOperations.class);
    private final AnalysisPushServiceImpl push = new AnalysisPushServiceImpl(broker);

    @Test
    void NSF05_aStoredRow_goesToTheMainAndComponentsTopics() {
        assertThat(push.publish(candle(MarketType.SPOT, OPEN), row(MarketType.SPOT, OPEN)))
                .isTrue();

        ArgumentCaptor<AnalysisUpdateResponse> main = ArgumentCaptor.forClass(AnalysisUpdateResponse.class);
        verify(broker).convertAndSend(eq(MAIN), main.capture());
        assertThat(main.getValue().market()).isEqualTo("SPOT");
        assertThat(main.getValue().symbol()).isEqualTo("BTCUSDT");
        assertThat(main.getValue().timeframe()).isEqualTo("1h");
        assertThat(main.getValue().openTime()).isEqualTo(OPEN);
        assertThat(main.getValue().calculatedAt()).isEqualTo(OPEN.plusSeconds(3600));
        assertThat(main.getValue().indicators().rsi14()).isEqualByComparingTo("55");
        assertThat(main.getValue().nearestSupport()).isEqualByComparingTo("90");
        assertThat(main.getValue().nearestResistance()).isEqualByComparingTo("110");
        ArgumentCaptor<AnalysisComponentsUpdateResponse> components =
                ArgumentCaptor.forClass(AnalysisComponentsUpdateResponse.class);
        verify(broker).convertAndSend(eq(MAIN + ".components"), components.capture());
        assertThat(components.getValue().openTime()).isEqualTo(OPEN);
        assertThat(components.getValue().components())
                .isEqualTo(new SpotComponentsResponse(
                        "v1", new BigDecimal("70"), new BigDecimal("60"), new BigDecimal("50"), new BigDecimal("40")));
    }

    /** D-126 (1): the main payload has no field for a score or the components; the client re-reads them by REST. */
    @Test
    void D126_theMainPayload_hasNoScoreAndNoComponents() {
        assertThat(Arrays.stream(AnalysisUpdateResponse.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .containsExactly(
                        "market",
                        "symbol",
                        "timeframe",
                        "openTime",
                        "calculatedAt",
                        "indicators",
                        "nearestSupport",
                        "nearestResistance");
    }

    @Test
    void NSF05_aFuturesRow_carriesTheDerivativesComponent() {
        push.publish(candle(MarketType.FUTURES, OPEN), row(MarketType.FUTURES, OPEN));

        ArgumentCaptor<AnalysisComponentsUpdateResponse> components =
                ArgumentCaptor.forClass(AnalysisComponentsUpdateResponse.class);
        verify(broker).convertAndSend(eq("/topic/analysis.FUTURES.BTCUSDT.1h.components"), components.capture());
        assertThat(((FuturesComponentsResponse) components.getValue().components()).derivatives())
                .isEqualByComparingTo("30");
    }

    /** A close may be announced again for a candle already processed: it is pushed once. */
    @Test
    void D126_aCandleAlreadyPushed_orAnOlderOne_isNotPushedAgain() {
        push.publish(candle(MarketType.SPOT, OPEN), row(MarketType.SPOT, OPEN));
        Instant earlier = OPEN.minusSeconds(3600);

        assertThat(push.publish(candle(MarketType.SPOT, OPEN), row(MarketType.SPOT, OPEN)))
                .isFalse();
        assertThat(push.publish(candle(MarketType.SPOT, earlier), row(MarketType.SPOT, earlier)))
                .isFalse();

        verify(broker, times(1)).convertAndSend(eq(MAIN), any(Object.class));
        verify(broker, times(1)).convertAndSend(eq(MAIN + ".components"), any(Object.class));
    }

    @Test
    void D126_theNextCandle_andAnotherSeries_arePushed() {
        push.publish(candle(MarketType.SPOT, OPEN), row(MarketType.SPOT, OPEN));
        Instant next = OPEN.plusSeconds(3600);

        assertThat(push.publish(candle(MarketType.SPOT, next), row(MarketType.SPOT, next)))
                .isTrue();
        assertThat(push.publish(candle(MarketType.FUTURES, OPEN), row(MarketType.FUTURES, OPEN)))
                .isTrue();

        verify(broker, times(2)).convertAndSend(eq(MAIN), any(Object.class));
    }

    /** A failed send is logged; the other topic is still pushed and nothing reaches the pipeline. */
    @Test
    void D126_aBrokerFailure_staysInThePush() {
        doThrow(new MessagingException("broker stopped")).when(broker).convertAndSend(anyString(), any(Object.class));

        assertThat(push.publish(candle(MarketType.SPOT, OPEN), row(MarketType.SPOT, OPEN)))
                .isTrue();
        verify(broker).convertAndSend(eq(MAIN + ".components"), any(Object.class));
    }

    private static CandleClosed candle(MarketType market, Instant open) {
        BigDecimal price = new BigDecimal("100");
        return new CandleClosed(
                PAIR,
                "BTCUSDT",
                market,
                "1h",
                open,
                open.plusSeconds(3599),
                price,
                price,
                price,
                price,
                BigDecimal.ONE,
                price);
    }

    private static StoredIndicators row(MarketType market, Instant open) {
        return new StoredIndicators(
                PAIR,
                open,
                null,
                null,
                null,
                null,
                new BigDecimal("55"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("90"),
                new BigDecimal("110"),
                new ComponentScores(
                        market,
                        "1h",
                        "v1",
                        new BigDecimal("70"),
                        new BigDecimal("60"),
                        new BigDecimal("50"),
                        new BigDecimal("40"),
                        market == MarketType.FUTURES ? new BigDecimal("30") : null),
                open.plusSeconds(3600));
    }
}

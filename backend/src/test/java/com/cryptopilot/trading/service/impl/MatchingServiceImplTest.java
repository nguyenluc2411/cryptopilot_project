package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PlanOrderPlacer;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The storage side of the engine: the compare-and-sets, the price a fill is stored at (slippage on TAKER fills only)
 * and the invalidation event.
 *
 * <p>Rule: NSF-07, BR-33, BR-35, BR-36, BR-68; D-111 (E1, E5); D-112 (R6); D-119.
 */
class MatchingServiceImplTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000b1");
    private static final Instant NOW = Instant.parse("2026-10-03T08:00:05Z");
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final MatchingWatermarkRepository watermarks = mock(MatchingWatermarkRepository.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlanOrderPlacer placer = mock(PlanOrderPlacer.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PlanOrderPlacer> placerProvider = mock(ObjectProvider.class);

    {
        when(placerProvider.getObject()).thenReturn(placer);
    }

    private final MatchingServiceImpl service = new MatchingServiceImpl(
            plans, watermarks, events, market, Clock.fixed(NOW, ZoneOffset.UTC), placerProvider);

    @Test
    void BR68_theCompareAndSetThatInvalidates_publishesTheEventWithTheOwnerTheReasonAndTheInstant() {
        TrackedEntry stop = stop(Direction.LONG);
        when(plans.invalidateIfActive(PLAN, AT, InvalidationReason.STOP_LOSS_REACHED, NOW))
                .thenReturn(1);
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.of(pair("0.01")));

        assertThat(service.invalidate(new Invalidation(stop, InvalidationReason.STOP_LOSS_REACHED, AT)))
                .isTrue();

        verify(events)
                .publishEvent(new TradingPlanInvalidated(
                        PLAN,
                        TrackedEntries.OWNER,
                        MarketType.FUTURES,
                        Direction.LONG,
                        "BTCUSDT",
                        InvalidationReason.STOP_LOSS_REACHED,
                        AT));
        verify(placer).cancel(TrackedEntries.OWNER, PLAN);
    }

    @Test
    void BR68_theCompareAndSetThatFindsNoActivePlan_invalidatesNothing_andPublishesNothing() {
        when(plans.invalidateIfActive(PLAN, AT, InvalidationReason.TAKE_PROFIT_REACHED, NOW))
                .thenReturn(0);

        assertThat(service.invalidate(
                        new Invalidation(stop(Direction.LONG), InvalidationReason.TAKE_PROFIT_REACHED, AT)))
                .isFalse();
        verifyNoInteractions(events);
        verifyNoInteractions(placer);
    }

    @Test
    void A04_theWatermark_isReadAndAdvancedByMarketAndPair() {
        UUID pair = UUID.randomUUID();
        when(watermarks.lastCandleOpenTime("FUTURES", pair)).thenReturn(Optional.of(AT));

        assertThat(service.watermark(MarketType.FUTURES, pair)).contains(AT);
        service.advanceWatermark(MarketType.SPOT, pair, AT);

        verify(watermarks).advance("SPOT", pair, AT, NOW);
    }

    @Test
    void NSF07_theBooksStartFromTheWaitingEntries() {
        List<TrackedEntry> entries = List.of(stop(Direction.SHORT));
        when(plans.findActiveWaitingEntries()).thenReturn(entries);

        assertThat(service.activeEntries()).isEqualTo(entries);
    }

    private static TrackedEntry stop(Direction direction) {
        boolean isLong = direction == Direction.LONG;
        return TrackedEntries.of(
                PLAN,
                MarketType.FUTURES,
                PAIR,
                direction,
                EntryType.STOP,
                new BigDecimal("100"),
                new BigDecimal(isLong ? "90" : "110"),
                new BigDecimal(isLong ? "120" : "80"),
                null,
                AT);
    }

    private static TradablePair pair(String tickSize) {
        PairFilters filters = new PairFilters(new BigDecimal(tickSize), new BigDecimal("0.001"), new BigDecimal("5"));
        return new TradablePair(PAIR, "BTCUSDT", MarketType.FUTURES, filters, true);
    }
}

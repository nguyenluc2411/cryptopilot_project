package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.ReplayLockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

/**
 * NSF-09: due plans are expired by compare-and-set and announced; a pair under replay keeps its plans (D-79).
 *
 * <p>Rule: NSF-09, BR-32; D-79.
 */
class PlanExpiryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00.123456789Z");
    private static final Instant STORED_NOW = Instant.parse("2026-10-25T09:00:00.123456Z");
    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID REPLAYING_PAIR = UUID.randomUUID();

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final ReplayLockService replays = mock(ReplayLockService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlanExpiryServiceImpl expiry =
            new PlanExpiryServiceImpl(plans, replays, market, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void NSF09_aDuePlan_isExpired_andAnnounced() {
        TradingPlan due = plan(PAIR);
        when(plans.findExpiring(STORED_NOW, Limit.of(10))).thenReturn(List.of(due));
        when(plans.expireIfActive(due.getId(), STORED_NOW)).thenReturn(1);
        when(market.tradablePair(PAIR, MarketType.FUTURES))
                .thenReturn(Optional.of(new TradablePair(
                        PAIR,
                        "BTCUSDT",
                        MarketType.FUTURES,
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")))));

        assertThat(expiry.expireDue(10)).isEqualTo(1);

        verify(events)
                .publishEvent(new TradingPlanExpired(
                        due.getId(),
                        due.getUserId(),
                        MarketType.FUTURES,
                        PAIR,
                        Direction.SHORT,
                        "BTCUSDT",
                        STORED_NOW));
    }

    /** D-79: a replay of the pair may still fill the plan from a candle before its expiry: it waits. */
    @Test
    void D79_aPlanOfAPairUnderReplay_isNotExpiredYet() {
        TradingPlan waiting = plan(REPLAYING_PAIR);
        when(plans.findExpiring(any(), any())).thenReturn(List.of(waiting));
        when(replays.isReplaying(MarketType.FUTURES, REPLAYING_PAIR)).thenReturn(true);

        assertThat(expiry.expireDue(10)).isZero();

        verify(plans, never()).expireIfActive(any(), any());
        verifyNoInteractions(events);
    }

    /** A plan filled, cancelled or invalidated first: the compare-and-set changes nothing and nothing is announced. */
    @Test
    void NSF09_aPlanDecidedFirst_isNotExpired_andTheEventStaysUnsent() {
        TradingPlan decided = plan(PAIR);
        when(plans.findExpiring(any(), any())).thenReturn(List.of(decided));
        when(plans.expireIfActive(eq(decided.getId()), any())).thenReturn(0);

        assertThat(expiry.expireDue(10)).isZero();

        verifyNoInteractions(events);
    }

    /** A pair whose symbol cannot be read still expires its plan; the event names no symbol. */
    @Test
    void NSF09_aPairWithoutASymbol_stillExpiresItsPlan() {
        TradingPlan due = plan(PAIR);
        when(plans.findExpiring(any(), any())).thenReturn(List.of(due));
        when(plans.expireIfActive(eq(due.getId()), any())).thenReturn(1);
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.empty());

        expiry.expireDue(10);

        verify(events)
                .publishEvent(new TradingPlanExpired(
                        due.getId(), due.getUserId(), MarketType.FUTURES, PAIR, Direction.SHORT, null, STORED_NOW));
    }

    private static TradingPlan plan(UUID pair) {
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getId()).thenReturn(UUID.randomUUID());
        when(plan.getUserId()).thenReturn(UUID.randomUUID());
        when(plan.getMarket()).thenReturn(MarketType.FUTURES);
        when(plan.getPairId()).thenReturn(pair);
        when(plan.getDirection()).thenReturn(Direction.SHORT);
        when(plan.getExpiresAt()).thenReturn(NOW.minusSeconds(60));
        return plan;
    }
}

package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.repository.MatchingWatermarkRepository;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
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
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PositionOpener positions = mock(PositionOpener.class);
    private final PositionCloser closer = mock(PositionCloser.class);
    private final TradeJournal journal = mock(TradeJournal.class);
    private final MatchingServiceImpl service = new MatchingServiceImpl(
            plans,
            watermarks,
            new TakerSlippage(settings, market),
            events,
            positions,
            closer,
            journal,
            market,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static final TrackedPosition OPENED = new TrackedPosition(
            UUID.randomUUID(),
            PLAN,
            TrackedEntries.OWNER,
            MarketType.SPOT,
            PAIR,
            Direction.LONG,
            new BigDecimal("100"),
            AT,
            new BigDecimal("95"),
            new BigDecimal("110"),
            null,
            0);

    @BeforeEach
    void opens() {
        when(positions.open(any())).thenReturn(OPENED);
    }

    @Test
    void BR34_theCompareAndSetThatChangesTheRow_isAFill_andOpensThePositionOfThePlanReadAgain() {
        TradingPlan executed = mock(TradingPlan.class);
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), LiquidityRole.MAKER, NOW))
                .thenReturn(1);
        when(plans.findById(PLAN)).thenReturn(Optional.of(executed));

        assertThat(service.fill(limitFill())).contains(OPENED);

        InOrder order = inOrder(plans, positions);
        order.verify(plans).executeIfActive(PLAN, AT, new BigDecimal("100"), LiquidityRole.MAKER, NOW);
        order.verify(plans).findById(PLAN);
        order.verify(positions).open(executed);
    }

    @Test
    void BR34_theCompareAndSetThatChangesNothing_isNoFill_andWritesNoJournalRecord() {
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), LiquidityRole.MAKER, NOW))
                .thenReturn(0);

        assertThat(service.fill(limitFill())).isEmpty();

        verify(positions, never()).open(any());
        verify(plans, never()).findById(any());
    }

    /** A resting LIMIT supplied liquidity: its own price, with no slippage and no read of the setting or the pair. */
    @Test
    void D111E1_aMakerFill_isStoredAtItsOwnPrice_withoutSlippage() {
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), LiquidityRole.MAKER, NOW))
                .thenReturn(1);
        when(plans.findById(PLAN)).thenReturn(Optional.of(mock(TradingPlan.class)));

        service.fill(limitFill());

        verifyNoInteractions(settings, market);
    }

    /**
     * TD 7.8 examples with 10 bps: a STOP LONG at 100 buys at 100.10, a STOP SHORT at 95 sells at 94.905, rounded down to
     * the 0.01 tick at 94.90; a gap fill at the open slips from the open. Stored as TAKER at the candle's open time,
     * with no cap (a stop-market order).
     */
    @ParameterizedTest(name = "{0} from {1}: {2}")
    @CsvSource({"LONG, 100, 100.10", "SHORT, 95, 94.90", "LONG, 102.37, 102.48", "SHORT, 97, 96.90"})
    void D112R6_aTakerFill_isStoredAtItsPriceMovedBySlippageAgainstTheTrader(
            Direction direction, String reference, String stored) {
        when(settings.integer("SLIPPAGE_BPS")).thenReturn(10);
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.of(pair("0.01")));
        TradingPlan executed = mock(TradingPlan.class);
        when(plans.executeIfActive(any(), any(), any(), any(), any())).thenReturn(1);
        when(plans.findById(PLAN)).thenReturn(Optional.of(executed));

        assertThat(service.fill(new Fill(stop(direction), LiquidityRole.TAKER, new BigDecimal(reference), AT)))
                .isPresent();

        // BR-34: the STOP fill opens its position too, from the plan read back with the stored price and role.
        InOrder order = inOrder(plans, positions);
        order.verify(plans)
                .executeIfActive(
                        eq(PLAN),
                        eq(AT),
                        argThat(price -> price.compareTo(new BigDecimal(stored)) == 0),
                        eq(LiquidityRole.TAKER),
                        eq(NOW));
        order.verify(plans).findById(PLAN);
        order.verify(positions).open(executed);
    }

    /** With the default of 0 bps a taker fill keeps its price, and the pair's tick is not read. */
    @Test
    void D111E5_withoutSlippage_aTakerFill_keepsItsPrice_andTheTickIsNotRead() {
        when(settings.integer("SLIPPAGE_BPS")).thenReturn(0);
        when(plans.executeIfActive(PLAN, AT, new BigDecimal("100"), LiquidityRole.TAKER, NOW))
                .thenReturn(1);
        when(plans.findById(PLAN)).thenReturn(Optional.of(mock(TradingPlan.class)));

        assertThat(service.fill(new Fill(stop(Direction.LONG), LiquidityRole.TAKER, new BigDecimal("100"), AT)))
                .isPresent();
        verifyNoInteractions(market);
    }

    /** A pair no longer tradable has no tick to round to: the fill fails and the worker retries it. */
    @Test
    void D112R6_aTakerFillWithSlippage_onAPairWithoutFilters_fails() {
        when(settings.integer("SLIPPAGE_BPS")).thenReturn(10);
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.empty());

        assertThatIllegalStateException()
                .isThrownBy(() ->
                        service.fill(new Fill(stop(Direction.LONG), LiquidityRole.TAKER, new BigDecimal("100"), AT)))
                .withMessageContaining(PAIR.toString());
        verify(plans, never()).executeIfActive(any(), any(), any(), any(), any());
    }

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
        verify(positions, never()).open(any());
    }

    @Test
    void BR68_theCompareAndSetThatFindsNoActivePlan_invalidatesNothing_andPublishesNothing() {
        when(plans.invalidateIfActive(PLAN, AT, InvalidationReason.TAKE_PROFIT_REACHED, NOW))
                .thenReturn(0);

        assertThat(service.invalidate(
                        new Invalidation(stop(Direction.LONG), InvalidationReason.TAKE_PROFIT_REACHED, AT)))
                .isFalse();
        verifyNoInteractions(events);
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

    /** NSF-07, D-119: the exit books start from the journal's OPEN positions, with their levels and version. */
    @Test
    void NSF07_theExitBooksStartFromTheOpenPositionsOfTheJournal() {
        OpenTrade open = new OpenTrade(
                OPENED.positionId(),
                PLAN,
                TrackedEntries.OWNER,
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("100"),
                AT,
                new BigDecimal("2"),
                LiquidityRole.MAKER,
                new BigDecimal("0.2"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                null,
                0);
        when(journal.openSimulatedTrades()).thenReturn(List.of(open));

        assertThat(service.openPositions()).containsExactly(OPENED);
    }

    /** BR-35: the close is the closer's compare-and-set; its answer is the service's. */
    @Test
    void BR35_aCloseIsTheClosersCompareAndSet() {
        PositionExit exit = new PositionExit(
                OPENED,
                new Exit(PLAN, CloseReason.TAKE_PROFIT, new BigDecimal("110"), AT),
                Excursion.at(new BigDecimal("100")));
        when(closer.close(exit)).thenReturn(true, false);

        assertThat(service.close(exit)).isTrue();
        assertThat(service.close(exit)).isFalse();
    }

    private static Fill limitFill() {
        TrackedEntry limit =
                TrackedEntries.limit(PLAN, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal("100"), AT);
        return new Fill(limit, LiquidityRole.MAKER, new BigDecimal("100"), AT);
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

package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedLevelChange;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.dto.request.ChangePositionLevelsRequest;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.PositionResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.PositionLevelsChanged;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.WarningType;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.FundingService;
import com.cryptopilot.user.PlanDefaults;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.RiskProfileParameters;
import com.cryptopilot.user.model.enums.RiskProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * TP-E: changing the stop loss and take profit of an open position. Futures LONG 2 at 100 (stop 95, take profit 110,
 * liquidation 80.32), last price 101, tick 0.01, capital 1,000 on BALANCED (1 % per trade, 4 % total).
 *
 * <p>Rule: BR-22, BR-28, BR-29, BR-30, BR-42; UC-21; D-63, D-119 (5), D-124; Q-79.
 */
class PositionLevelServiceImplTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000e2");
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:30.123456789Z");
    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    private static final RiskProfileParameters BALANCED =
            new RiskProfileParameters(RiskProfile.BALANCED, BigDecimal.ONE, 5, new BigDecimal("4"), 60);

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final TradeJournal journal = mock(TradeJournal.class);
    private final MarketApi market = mock(MarketApi.class);
    private final RiskProfileApi riskProfiles = mock(RiskProfileApi.class);
    private final UserLock lock = mock(UserLock.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FundingService funding = mock(FundingService.class);
    private final PositionLevelServiceImpl service = new PositionLevelServiceImpl(
            plans,
            journal,
            market,
            riskProfiles,
            lock,
            new PositionViews(journal, market, new FeeRates(settings), funding, settings),
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private TradingPlan plan;
    private OpenTrade trade;

    @BeforeEach
    void setUp() {
        given(executed(PlanFixture.spot().stop("95").takeProfit("110")));
        trade = trade(Direction.LONG, MarketType.SPOT, "95", "110", null, 3);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        when(market.tradablePair(eq(PAIR), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(PAIR, "BTCUSDT", call.getArgument(1), FILTERS)));
        when(market.currentLastPrice(any(), eq("BTCUSDT"))).thenReturn(Optional.of(new BigDecimal("101")));
        when(journal.changeSimulatedLevels(any())).thenAnswer(call -> {
            SimulatedLevelChange change = call.getArgument(0);
            return Optional.of(withLevels(trade, change.newStopLoss(), change.newTakeProfit(), trade.version() + 1));
        });
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, new BigDecimal("1000"), null));
        when(settings.decimal(any())).thenReturn(new BigDecimal("0.001"));
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(BigDecimal.ZERO);
        when(funding.fundingSoFar(any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));
    }

    /**
     * A tightened stop and a new take profit, rounded to the tick: stored with a compare-and-set at the version read,
     * the change instant to the microsecond, and the engine told after the commit with the row's new version.
     */
    @Test
    void D124_aValidChange_isStoredAtTheVersionRead_andTheEngineIsTold() {
        PositionResponse position = service.changeLevels(USER, plan.getId(), request("97.004", "112.006"))
                .position();

        SimulatedLevelChange change = stored();
        assertThat(change.positionId()).isEqualTo(trade.positionId());
        assertThat(change.version()).isEqualTo(3);
        assertThat(change.oldStopLoss()).isEqualByComparingTo("95");
        assertThat(change.oldTakeProfit()).isEqualByComparingTo("110");
        assertThat(change.newStopLoss()).isEqualByComparingTo("97");
        assertThat(change.newTakeProfit()).isEqualByComparingTo("112.01");
        assertThat(change.widensStop()).isFalse();
        assertThat(change.changedAt()).isEqualTo(Instant.parse("2026-10-25T09:00:30.123456Z"));
        ArgumentCaptor<PositionLevelsChanged> told = ArgumentCaptor.forClass(PositionLevelsChanged.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().position().version()).isEqualTo(4);
        assertThat(told.getValue().position().stopLoss()).isEqualByComparingTo("97");
        assertThat(told.getValue().change()).isEqualTo(change.history());
        assertThat(position.levelWarnings()).isEmpty();
    }

    /** D-63: the Trader's lock is taken before anything is read, so a widened stop and an activation serialise. */
    @Test
    void D63_theChange_runsUnderTheTradersLock() {
        service.changeLevels(USER, plan.getId(), request("97", null));

        InOrder order = inOrder(lock, plans, journal);
        order.verify(lock).lock(USER);
        order.verify(plans).findByIdAndUserId(plan.getId(), USER);
        order.verify(journal).changeSimulatedLevels(any());
    }

    /** Only the stop loss sent: the take profit is kept. Only the take profit sent: the stop loss is kept. */
    @Test
    void D124_aLevelNotSent_isKept() {
        service.changeLevels(USER, plan.getId(), request("97", null));
        assertThat(stored().newTakeProfit()).isEqualByComparingTo("110");

        org.mockito.Mockito.clearInvocations(journal);
        service.changeLevels(USER, plan.getId(), request(null, "108"));
        assertThat(stored().newStopLoss()).isEqualByComparingTo("95");
    }

    /** BR-42: widened against the ORIGINAL stop (the plan's 95), not against the current one. */
    @ParameterizedTest(name = "current {0} → {1}: widened {2}")
    @CsvSource({"95, 94, true", "97, 96, false", "93, 94, true", "95, 95.5, false"})
    void BR42_stopWidened_isAgainstTheOriginalStop(String current, String next, boolean widened) {
        trade = trade(Direction.LONG, MarketType.SPOT, current, "110", null, 3);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));

        service.changeLevels(USER, plan.getId(), request(next, null));

        assertThat(stored().widensStop()).isEqualTo(widened);
    }

    /** A request equal to the current levels after rounding writes nothing and needs no price. */
    @Test
    void D124_levelsEqualToTheCurrentOnes_changeNothing() {
        when(market.currentLastPrice(any(), any())).thenReturn(Optional.empty());

        service.changeLevels(USER, plan.getId(), request("95.001", "110"));

        verify(journal, never()).changeSimulatedLevels(any());
        verifyNoInteractions(events);
    }

    @Test
    void D124_neitherLevel_isMsg01OnTheStopLoss() {
        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request(null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class, refused -> assertThat(refused.errors())
                        .isEqualTo(Map.of("stopLoss", "MSG01")));
        verifyNoInteractions(lock);
    }

    @Test
    void D124_anotherTradersPlan_isNotFound() {
        assertThatThrownBy(() -> service.changeLevels(UUID.randomUUID(), plan.getId(), request("97", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void D124_aPairNoLongerTradable_isNotFound() {
        when(market.tradablePair(eq(PAIR), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("97", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** A plan that is not EXECUTED, or whose position is no longer OPEN, answers MSG43 and nothing changes. */
    @Test
    void D124_noOpenPosition_isMsg43() {
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of());
        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("97", null)))
                .isInstanceOf(IllegalPlanStateException.class);

        TradingPlan draft = TradingPlan.draft(USER, PAIR, PlanFixture.spot().build(), details(), List.of());
        when(plans.findByIdAndUserId(draft.getId(), USER)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.changeLevels(USER, draft.getId(), request("97", null)))
                .isInstanceOf(IllegalPlanStateException.class);

        verify(journal, never()).changeSimulatedLevels(any());
    }

    /** The exit committed between the read and the compare-and-set: MSG43, the engine is not told. */
    @Test
    void D124_aCompareAndSetLostToAnExit_isMsg43() {
        doReturn(Optional.empty()).when(journal).changeSimulatedLevels(any());

        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("97", null)))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID));
        verifyNoInteractions(events);
    }

    @Test
    void D124_withoutACurrentPrice_isMsg43Unavailable() {
        when(market.currentLastPrice(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("97", null)))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
    }

    /** A level the last price 101 has reached, or a Futures stop at the liquidation price: MSG16 on each field. */
    @Test
    void BR22_BR28_aRefusedLevel_isMsg16OnItsField_andNothingChanges() {
        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("101", "100.99")))
                .isInstanceOfSatisfying(FieldValidationException.class, refused -> assertThat(refused.errors())
                        .isEqualTo(Map.of("stopLoss", "MSG16", "takeProfit", "MSG16")));

        trade = trade(Direction.LONG, MarketType.FUTURES, "95", "110", "80.32", 3);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("80.32", null)))
                .isInstanceOfSatisfying(FieldValidationException.class, refused -> assertThat(refused.errors())
                        .isEqualTo(Map.of("stopLoss", "MSG16")));

        verify(journal, never()).changeSimulatedLevels(any());
    }

    /**
     * Q-79: a stop moved away beyond the profile's limits warns and is stored: at 94 the position risks 2 × 6 = 12, 1.2
     * % of 1,000 above 1 %; with 30 in ACTIVE plans the total is 4.2 %, above 4 %.
     */
    @Test
    void BR29_Q79_aStopWidenedBeyondTheLimits_warns_andIsStored() {
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("30"));
        when(journal.openSimulatedTrades(USER))
                .thenReturn(List.of(trade))
                .thenReturn(List.of(withLevels(trade, new BigDecimal("94"), trade.takeProfit(), 4)));

        PositionResponse position =
                service.changeLevels(USER, plan.getId(), request("94", null)).position();

        verify(journal).changeSimulatedLevels(any());
        assertThat(position.levelWarnings())
                .extracting(PlanWarningResponse::type)
                .containsExactly(WarningType.OVERSIZED_POSITION, WarningType.TOTAL_OPEN_RISK);
    }

    /** A stop moved closer to the entry never warns; a profile without capital gives no shares to warn on. */
    @Test
    void BR29_aTightenedStop_orNoCapital_warnsNothing() {
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("100"));
        assertThat(service.changeLevels(USER, plan.getId(), request("96", null))
                        .position()
                        .levelWarnings())
                .isEmpty();

        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, null, null));
        trade = trade(Direction.LONG, MarketType.SPOT, "96", "110", null, 4);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        assertThat(service.changeLevels(USER, plan.getId(), request("90", null))
                        .position()
                        .levelWarnings())
                .isEmpty();
    }

    /** A position without a take profit gets one; a profile capital of 0 gives no shares to warn on. */
    @Test
    void D124_aPositionWithoutATakeProfit_getsOne_andACapitalOfZeroWarnsNothing() {
        trade = new OpenTrade(
                trade.positionId(),
                trade.planId(),
                USER,
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("100"),
                NOW.minus(Duration.ofHours(1)),
                new BigDecimal("2"),
                LiquidityRole.TAKER,
                new BigDecimal("0.2"),
                new BigDecimal("95"),
                null,
                null,
                3);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, BigDecimal.ZERO, null));

        PositionResponse position =
                service.changeLevels(USER, plan.getId(), request("90", "120")).position();

        assertThat(stored().newTakeProfit()).isEqualByComparingTo("120");
        assertThat(position.levelWarnings()).isEmpty();
    }

    /** A SHORT: the stop above, the take profit below the last price. */
    @Test
    void BR22_aShort_takesItsLevelsMirrored() {
        given(executed(PlanFixture.futuresShort()));
        trade = trade(Direction.SHORT, MarketType.FUTURES, "105", "90", "119.5", 3);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));

        service.changeLevels(USER, plan.getId(), request("103", "95"));

        assertThat(stored().newStopLoss()).isEqualByComparingTo("103");
        assertThatThrownBy(() -> service.changeLevels(USER, plan.getId(), request("100", null)))
                .isInstanceOf(FieldValidationException.class);
    }

    private void given(TradingPlan executed) {
        plan = executed;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(journal.positionOf(plan.getId()))
                .thenReturn(Optional.of(RecordedPosition.open(
                        plan.getId(), new BigDecimal("100"), new BigDecimal("2"), NOW, new BigDecimal("0.2"), null)));
    }

    private SimulatedLevelChange stored() {
        ArgumentCaptor<SimulatedLevelChange> change = ArgumentCaptor.forClass(SimulatedLevelChange.class);
        verify(journal).changeSimulatedLevels(change.capture());
        return change.getValue();
    }

    private static ChangePositionLevelsRequest request(String stopLoss, String takeProfit) {
        return new ChangePositionLevelsRequest(
                stopLoss == null ? null : new BigDecimal(stopLoss),
                takeProfit == null ? null : new BigDecimal(takeProfit));
    }

    private static TradingPlan executed(PlanFixture fixture) {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, fixture.entryType(EntryType.LIMIT).build(), details(), List.of());
        plan.activate(fixture.build(), List.of(), NOW.minus(Duration.ofHours(1)));
        plan.markExecuted(NOW.minus(Duration.ofHours(1)), new BigDecimal("100"), LiquidityRole.MAKER);
        return plan;
    }

    private static PlanDetails details() {
        return new PlanDetails(
                EntryType.LIMIT, NOW.plus(Duration.ofDays(7)), null, null, null, AnalysisTimeframe.ONE_HOUR);
    }

    private OpenTrade trade(
            Direction direction, MarketType marketType, String stop, String target, String liquidation, long version) {
        return new OpenTrade(
                UUID.fromString("019b76da-a800-7000-8000-0000000000e3"),
                plan.getId(),
                USER,
                PAIR,
                marketType,
                direction,
                new BigDecimal("100"),
                NOW.minus(Duration.ofHours(1)),
                new BigDecimal("2"),
                LiquidityRole.TAKER,
                new BigDecimal("0.2"),
                new BigDecimal(stop),
                new BigDecimal(target),
                liquidation == null ? null : new BigDecimal(liquidation),
                version);
    }

    private static OpenTrade withLevels(OpenTrade trade, BigDecimal stop, BigDecimal target, long version) {
        return new OpenTrade(
                trade.positionId(),
                trade.planId(),
                trade.userId(),
                trade.pairId(),
                trade.market(),
                trade.direction(),
                trade.entryPrice(),
                trade.entryTime(),
                trade.quantity(),
                trade.entryLiquidity(),
                trade.entryFee(),
                stop,
                target,
                trade.liquidationPrice(),
                version);
    }
}

package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.StatusChangeResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.exception.PlanActivationBlockedException;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PlanTab;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.user.PlanDefaults;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.RiskProfileParameters;
import com.cryptopilot.user.model.enums.RiskProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumSet;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The plan use cases against the real calculations, with the other modules, the repository and the lock replaced: a
 * BALANCED Trader (1 %, 5x, 4 %) with a default capital of 1,000 on a pair with tick 0.01, step 0.001 and minimum
 * notional 5.
 */
class TradingPlanServiceImplTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-00000000a001");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));
    private static final RiskProfileParameters BALANCED =
            new RiskProfileParameters(RiskProfile.BALANCED, BigDecimal.ONE, 5, new BigDecimal("4"), 65);

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final MarketApi market = mock(MarketApi.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final ActivePlanLock lock = mock(ActivePlanLock.class);
    private final RiskProfileApi riskProfiles = mock(RiskProfileApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private TradingPlanServiceImpl service;

    /** The last plan the service saved. */
    private TradingPlan saved;

    @BeforeEach
    void setUp() {
        service = new TradingPlanServiceImpl(
                plans,
                new PlanCalculationAssembler(market, settings),
                lock,
                market,
                riskProfiles,
                entitlements,
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);
        when(market.tradablePair(eq(PAIR), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(PAIR, "BTCUSDT", call.getArgument(1), FILTERS)));
        when(market.leverageBrackets(PAIR))
                .thenReturn(PlanFixture.BRACKETS.stream()
                        .map(b -> new LeverageTier(
                                b.bracketNo(),
                                b.notionalFloor(),
                                b.notionalCap(),
                                b.maxLeverage(),
                                b.maintenanceMarginRate(),
                                b.maintenanceAmount()))
                        .toList());
        when(market.currentFundingRate("BTCUSDT")).thenReturn(Optional.empty());
        when(settings.decimal(PlanCalculationAssembler.LOW_RR)).thenReturn(new BigDecimal("1.5"));
        when(settings.decimal(PlanCalculationAssembler.WIDE_STOP_LOSS)).thenReturn(BigDecimal.TEN);
        when(settings.decimal(PlanCalculationAssembler.HIGH_FUNDING_RATE)).thenReturn(new BigDecimal("0.001"));
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, new BigDecimal("1000"), null));
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(BigDecimal.ZERO);
        when(plans.save(any())).thenAnswer(call -> {
            saved = call.getArgument(0);
            return saved;
        });
    }

    // ------------------------------------------------------------------------------------------
    // The live risk panel (SRS 3.5.1)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR30_thePanel_roundsPricesToTheTickAndTakesTheDefaultsOfTheProfile() {
        PlanCalculationResponse panel = service.calculate(USER, spotLimit("100.004", "94.996", "110.001"));

        assertThat(panel.entryPrice()).isEqualByComparingTo("100.00");
        assertThat(panel.capital()).isEqualByComparingTo("1000");
        assertThat(panel.riskPercent())
                .as("no default risk % on the profile screen: the risk per trade of the profile")
                .isEqualByComparingTo("1");
        assertThat(panel.snapshot().positionQuantity()).isEqualByComparingTo("2");
        assertThat(panel.snapshot().notionalValue()).isEqualByComparingTo("200");
        assertThat(panel.snapshot().initialMargin()).isNull();
        assertThat(panel.blocksActivation()).isFalse();
        verifyNoInteractions(lock);
        verify(plans, never()).save(any());
    }

    @Test
    void SRS325_theDefaultRiskPercentOfTheProfileScreen_isTakenBeforeTheRiskPerTrade() {
        when(riskProfiles.planDefaultsOf(USER))
                .thenReturn(new PlanDefaults(BALANCED, new BigDecimal("1000"), new BigDecimal("0.5")));

        assertThat(service.calculate(USER, spotLimit("100", "95", "110")).riskPercent())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void theValuesEntered_winOverTheDefaults() {
        CreateTradingPlanRequest request =
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", "2000", "2", null);

        PlanCalculationResponse panel = service.calculate(USER, request);

        assertThat(panel.capital()).isEqualByComparingTo("2000");
        assertThat(panel.riskPercent()).isEqualByComparingTo("2");
    }

    @Test
    void MSG01_withoutACapitalAndWithoutADefault_theCapitalIsRequired() {
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, null, null));

        assertThatThrownBy(() -> service.calculate(USER, spotLimit("100", "95", "110")))
                .satisfies(e -> assertFields(e, Map.of("capital", "MSG01")));
    }

    @Test
    void MSG01_aLimitPlan_needsAnEntryPrice() {
        assertThatThrownBy(() -> service.calculate(USER, spotLimit(null, "95", "110")))
                .satisfies(e -> assertFields(e, Map.of("entryPrice", "MSG01")));
    }

    @Test
    void BR33_aMarketPlan_isCalculatedAtTheCurrentLastPrice() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));

        PlanCalculationResponse panel = service.calculate(USER, spotMarket("95", "110"));

        assertThat(panel.entryPrice()).isEqualByComparingTo("101");
    }

    @Test
    void BR33_withoutACurrentLastPrice_aMarketPlanCannotBeCalculated() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(USER, spotMarket("95", "110")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
    }

    @Test
    void MSG41_aPairThatIsNotEnabledOnTheMarket_isNotFound() {
        when(market.tradablePair(PAIR, MarketType.SPOT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(USER, spotLimit("100", "95", "110")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void MSG16_aPriceOrderTheCalculationRejects_isReportedOnItsField() {
        assertThatThrownBy(() -> service.calculate(USER, spotLimit("100", "101", "110")))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
    }

    @Test
    void BR21_aSpotPlanWithLeverage_isReportedOnTheLeverage() {
        CreateTradingPlanRequest request =
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, null, 3);

        assertThatThrownBy(() -> service.calculate(USER, request))
                .satisfies(e -> assertFields(e, Map.of("leverage", "MSG01")));
    }

    // ------------------------------------------------------------------------------------------
    // Futures (BR-26 to BR-28, BR-62)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR62_aFuturesPlan_needsTheFuturesFeatureOfThePlan() {
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "FREE has no Futures", "PRO"))
                .when(entitlements)
                .requireFeature(USER, Feature.FUTURES_ANALYSIS);

        assertThatThrownBy(() -> service.calculate(USER, futuresLimit(5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED));
        verify(market, never()).tradablePair(any(), any());
    }

    @Test
    void BR27_aFuturesPlan_isCalculatedWithItsMarginAndLiquidationPrice() {
        PlanCalculationResponse panel = service.calculate(USER, futuresLimit(5));

        verify(entitlements).requireFeature(USER, Feature.FUTURES_ANALYSIS);
        assertThat(panel.snapshot().initialMargin()).isEqualByComparingTo("40");
        assertThat(panel.snapshot().maintenanceMarginRateUsed()).isEqualByComparingTo("0.004");
        assertThat(panel.snapshot().estimatedLiquidationPrice()).isNotNull();
    }

    @Test
    void BR26_aFuturesPairWithoutLeverageBrackets_cannotBeSized() {
        when(market.leverageBrackets(PAIR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.calculate(USER, futuresLimit(5)))
                .satisfies(e -> assertFields(e, Map.of("leverage", "MSG15")));
    }

    @Test
    void BR26_aLeverageAboveTheBracket_isReportedOnTheLeverage() {
        when(market.leverageBrackets(PAIR))
                .thenReturn(List.of(new LeverageTier(
                        1, BigDecimal.ZERO, new BigDecimal("50000"), 3, new BigDecimal("0.004"), BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.calculate(USER, futuresLimit(5)))
                .satisfies(e -> assertFields(e, Map.of("leverage", "MSG15")));
    }

    @Test
    void BR29_theCurrentFundingRate_reachesTheWarningRules() {
        when(market.currentFundingRate("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("0.002")));

        assertThat(service.calculate(USER, futuresLimit(5)).warnings())
                .extracting(PlanWarningResponse::type)
                .contains(WarningType.HIGH_FUNDING_RATE);
    }

    // ------------------------------------------------------------------------------------------
    // Creating, editing, activating, cancelling (UC-16 to UC-19)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC16_aNewPlan_isSavedAsADraftThatExpiresInSevenDays() {
        TradingPlanResponse plan = service.create(USER, spotLimit("100", "95", "110"));

        assertThat(plan.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.editable()).isTrue();
        assertThat(plan.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(plan.statusHistory())
                .as("the creation instant is written on insert; a mocked save writes none")
                .isEmpty();
        verify(plans).save(any());
        verifyNoInteractions(lock);
        verify(entitlements, never()).requireWithinLimit(any(), any(), anyLong());
    }

    @Test
    void UC16_aMarketPlan_hasNoExpiryUnlessOneIsGiven() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));

        assertThat(service.create(USER, spotMarket("95", "110")).expiresAt()).isNull();
    }

    @Test
    void UC16_anExpiryGiven_isKept() {
        Instant expiry = NOW.plus(Duration.ofDays(2));
        CreateTradingPlanRequest request = new CreateTradingPlanRequest(
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                EntryType.LIMIT,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                null,
                null,
                null,
                expiry,
                "note",
                false);

        TradingPlanResponse plan = service.create(USER, request);

        assertThat(plan.expiresAt()).isEqualTo(expiry);
        assertThat(plan.note()).isEqualTo("note");
    }

    @Test
    void BR62_saveAndActivate_locksBeforeItCountsAndActivates() {
        when(plans.countActive(USER)).thenReturn(2L);

        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.statusHistory()).containsExactly(new StatusChangeResponse(PlanStatus.ACTIVE, NOW));
        InOrder order = inOrder(lock, plans, entitlements);
        order.verify(lock).lock(USER);
        order.verify(plans).countActive(USER);
        order.verify(entitlements).requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 2L);
        order.verify(plans).save(any());
        verify(events)
                .publishEvent(new TradingPlanActivated(
                        saved.getId(),
                        MarketType.SPOT,
                        PAIR,
                        Direction.LONG,
                        EntryType.LIMIT,
                        saved.getEntryPrice(),
                        NOW));
    }

    @Test
    void MSG27_saveAndActivate_overThePlanLimit_savesNothing() {
        when(plans.countActive(USER)).thenReturn(3L);
        doThrow(new BusinessException(ErrorCode.PLAN_LIMIT_REACHED, "FREE allows 3", 3, "plans", "FREE"))
                .when(entitlements)
                .requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 3L);

        assertThatThrownBy(() -> service.create(USER, activating(spotLimit("100", "95", "110"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED));
        verify(plans, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void MSG18_saveAndActivate_withABlockingWarning_isRefused() {
        // Risk 10 % of 100 over a stop of 5 buys 2 units: a notional of 200 above the capital of 100 (BR-25).
        CreateTradingPlanRequest blocked = activating(
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", "100", "10", null));

        assertThatThrownBy(() -> service.create(USER, blocked)).isInstanceOf(PlanActivationBlockedException.class);
        verify(plans, never()).save(any());
    }

    @Test
    void UC17_aDraft_isEditedWithItsPairKept() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        TradingPlanResponse edited = service.update(USER, plan.getId(), update("100", "96", "120", false));

        assertThat(edited.stopLoss()).isEqualByComparingTo("96");
        assertThat(edited.pairId()).isEqualTo(PAIR);
        assertThat(edited.status()).isEqualTo(PlanStatus.DRAFT);
        verify(plans).activeRiskExcluding(USER, plan.getId());
        verifyNoInteractions(lock);
    }

    @Test
    void UC17_aDraftEditedAndActivated_isCountedUnderTheLock() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        TradingPlanResponse edited = service.update(USER, plan.getId(), update("100", "96", "120", true));

        assertThat(edited.status()).isEqualTo(PlanStatus.ACTIVE);
        InOrder order = inOrder(lock, plans, entitlements);
        order.verify(lock).lock(USER);
        order.verify(plans).findByIdAndUserId(plan.getId(), USER);
        order.verify(entitlements).requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 0L);
        verify(events).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void MSG41_anotherTradersPlan_isNotFound() {
        UUID planId = UUID.randomUUID();
        when(plans.findByIdAndUserId(planId, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER, planId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(USER, planId, update("100", "96", "120", false)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.activate(USER, planId)).isInstanceOf(ResourceNotFoundException.class);
        when(plans.findForUpdate(planId, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(USER, planId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void UC18_activation_locksCountsAndRecalculatesInThatOrder() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(plans.countActive(USER)).thenReturn(1L);

        TradingPlanResponse active = service.activate(USER, plan.getId());

        assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
        InOrder order = inOrder(lock, plans, entitlements, riskProfiles);
        order.verify(lock).lock(USER);
        order.verify(plans).findByIdAndUserId(plan.getId(), USER);
        order.verify(plans).countActive(USER);
        order.verify(entitlements).requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 1L);
        order.verify(riskProfiles).planDefaultsOf(USER);
        order.verify(plans).save(plan);
        verify(events).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void BR32_activatingAPlanThatIsNotADraft_isAStateErrorBeforeAnyCount() {
        TradingPlan plan = draft();
        plan.cancel(NOW);
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.activate(USER, plan.getId())).isInstanceOf(IllegalPlanStateException.class);
        verify(plans, never()).countActive(any());
    }

    @Test
    void BR62_aFreeTraderAtTheLimit_cannotActivate() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(plans.countActive(USER)).thenReturn(3L);
        doThrow(new BusinessException(ErrorCode.PLAN_LIMIT_REACHED, "FREE allows 3", 3, "plans", "FREE"))
                .when(entitlements)
                .requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 3L);

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verifyNoInteractions(events);
    }

    @ParameterizedTest(name = "{0} at {1} past a stop of {2}")
    @CsvSource({"LONG, 94, 95, 110", "SHORT, 106, 105, 90"})
    void MSG16_aMarketPlanRepricedPastItsStop_isRefusedAtActivationOnTheStopLoss(
            Direction direction, String lastPrice, String stop, String takeProfit) {
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));
        service.create(
                USER,
                create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, stop, takeProfit, null, null, 5));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(lastPrice)));

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100");
    }

    @Test
    void BR29_theRiskOfTheOtherActivePlans_raisesTotalOpenRisk() {
        // The plan risks 10; with 35 already at risk the total is 4.5 % of 1,000, above BALANCED's 4 %.
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("35"));

        assertThat(service.calculate(USER, spotLimit("100", "95", "110")).warnings())
                .extracting(PlanWarningResponse::type, PlanWarningResponse::severity)
                .contains(org.assertj.core.groups.Tuple.tuple(WarningType.TOTAL_OPEN_RISK, WarningSeverity.WARNING));
    }

    @Test
    void UC19_aPlan_isCancelledAtTheInstantOfTheClock() {
        TradingPlan plan = draft();
        when(plans.findForUpdate(plan.getId(), USER)).thenReturn(Optional.of(plan));

        TradingPlanResponse cancelled = service.cancel(USER, plan.getId());

        assertThat(cancelled.status()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(cancelled.editable()).isFalse();
        assertThat(cancelled.statusHistory()).containsExactly(new StatusChangeResponse(PlanStatus.CANCELLED, NOW));
        verify(events).publishEvent(new TradingPlanCancelled(plan.getId(), plan.getMarket(), plan.getPairId()));
    }

    @Test
    void SCR18_aPlan_isReadWithItsWarningsAndWhetherTheyBlock() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        TradingPlanResponse read = service.get(USER, plan.getId());

        assertThat(read.id()).isEqualTo(plan.getId());
        assertThat(read.snapshot().riskAmount()).isEqualByComparingTo("10");
        assertThat(read.blocksActivation()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // The list (SCR-16, CR-04)
    // ------------------------------------------------------------------------------------------

    @Test
    void CR04_theList_isTwentyPerPageNewestFirstInEveryStatusByDefault() {
        TradingPlan plan = draft();
        when(plans.search(eq(USER), any(), any(), any(boolean.class), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(plan), PageRequest.of(0, 20), 41));

        PageResponse<TradingPlanSummaryResponse> page =
                service.list(USER, new PlanListQuery(null, null, null, null, null, null, null));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(41);
        assertThat(page.items()).extracting(TradingPlanSummaryResponse::id).containsExactly(plan.getId());
        assertThat(page.items().get(0).riskAmount()).isEqualByComparingTo("10");
        ArgumentCaptor<Collection<PlanStatus>> statuses = statusCaptor();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(plans)
                .search(
                        eq(USER),
                        statuses.capture(),
                        eq(EnumSet.allOf(MarketType.class)),
                        eq(true),
                        any(),
                        eq(Instant.parse("2000-01-01T00:00:00Z")),
                        eq(Instant.parse("9999-01-01T00:00:00Z")),
                        pageable.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PlanStatus.class));
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 20));
    }

    @Test
    void SCR16_theFilters_reachTheQuery() {
        Instant from = NOW.minus(Duration.ofDays(30));
        when(plans.search(any(), any(), any(), any(boolean.class), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

        service.list(USER, new PlanListQuery(PlanTab.CANCELLED_EXPIRED, PAIR, MarketType.SPOT, from, NOW, 2, 5));

        verify(plans)
                .search(
                        USER,
                        EnumSet.of(PlanStatus.CANCELLED, PlanStatus.EXPIRED),
                        EnumSet.of(MarketType.SPOT),
                        false,
                        PAIR,
                        from,
                        NOW,
                        PageRequest.of(1, 5));
    }

    @ParameterizedTest(name = "page {0}, pageSize {1}")
    @CsvSource({"0, 20, page", "1, 0, pageSize", "1, 101, pageSize"})
    void MSG15_aPageOutsideItsRange_isReportedOnItsParameter(int page, int pageSize, String field) {
        assertThatThrownBy(() -> service.list(USER, new PlanListQuery(null, null, null, null, null, page, pageSize)))
                .satisfies(e -> assertFields(e, Map.of(field, "MSG15")));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"}) // A captor of a generic type has no class literal (T-039).
    private static ArgumentCaptor<Collection<PlanStatus>> statusCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
    }

    /** A Spot LIMIT draft (E 100, S 95, T 110) created through the service. */
    private TradingPlan draft() {
        service.create(USER, spotLimit("100", "95", "110"));
        return saved;
    }

    private static void assertFields(Throwable thrown, Map<String, String> errors) {
        assertThat(thrown).isInstanceOf(FieldValidationException.class);
        assertThat(((FieldValidationException) thrown).errors()).isEqualTo(errors);
    }

    private static CreateTradingPlanRequest spotLimit(String entry, String stop, String takeProfit) {
        return create(
                PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, entry, stop, takeProfit, null, null, null);
    }

    private static CreateTradingPlanRequest spotMarket(String stop, String takeProfit) {
        return create(
                PAIR, MarketType.SPOT, Direction.LONG, EntryType.MARKET, null, stop, takeProfit, null, null, null);
    }

    private static CreateTradingPlanRequest futuresLimit(int leverage) {
        return create(
                PAIR, MarketType.FUTURES, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, null, leverage);
    }

    private static CreateTradingPlanRequest create(
            UUID pair,
            MarketType market,
            Direction direction,
            EntryType entryType,
            String entry,
            String stop,
            String takeProfit,
            String capital,
            String riskPercent,
            Integer leverage) {
        return new CreateTradingPlanRequest(
                pair,
                market,
                direction,
                entryType,
                decimal(entry),
                decimal(stop),
                decimal(takeProfit),
                decimal(capital),
                decimal(riskPercent),
                leverage,
                null,
                null,
                false);
    }

    private static CreateTradingPlanRequest activating(CreateTradingPlanRequest r) {
        return new CreateTradingPlanRequest(
                r.pairId(),
                r.market(),
                r.direction(),
                r.entryType(),
                r.entryPrice(),
                r.stopLoss(),
                r.takeProfit(),
                r.capital(),
                r.riskPercent(),
                r.leverage(),
                r.expiresAt(),
                r.note(),
                true);
    }

    private static UpdateTradingPlanRequest update(String entry, String stop, String takeProfit, boolean activate) {
        return new UpdateTradingPlanRequest(
                MarketType.SPOT,
                Direction.LONG,
                EntryType.LIMIT,
                decimal(entry),
                decimal(stop),
                decimal(takeProfit),
                null,
                null,
                null,
                null,
                null,
                activate);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

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
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
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
import com.cryptopilot.trading.model.enums.LiquidityRole;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
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
    private final UserLock lock = mock(UserLock.class);
    private final RiskProfileApi riskProfiles = mock(RiskProfileApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SetupStyleSource styles = mock(SetupStyleSource.class);

    @SuppressWarnings("unchecked") // TP-A: a mock of a generic interface
    private final ObjectProvider<SetupStyleSource> styleProvider = mock(ObjectProvider.class);

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
                events,
                settings,
                styleProvider);
        when(styleProvider.getIfAvailable()).thenReturn(styles);
        when(settings.decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_BLOCK_R)).thenReturn(new BigDecimal("0.75"));
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

    /** Futures has no last trade price in the cache: a MARKET plan there is priced at the current mark price. */
    @Test
    void BR33_aFuturesMarketPlan_isCalculatedAtTheCurrentMarkPrice() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));

        PlanCalculationResponse panel = service.calculate(USER, futuresMarket());

        assertThat(panel.entryPrice()).isEqualByComparingTo("100.5");
        verify(market, never()).currentLastPrice(eq(MarketType.FUTURES), any());
    }

    /** No current mark price: the existing 503 MSG43 path, whatever else the pair has. */
    @Test
    void BR33_withoutACurrentMarkPrice_aFuturesMarketPlanCannotBeCalculated() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.empty());
        when(market.leverageBrackets(PAIR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.calculate(USER, futuresMarket()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE);
                    assertThat(ErrorCode.MARKET_PRICE_UNAVAILABLE.status().value())
                            .isEqualTo(503);
                    assertThat(ErrorCode.MARKET_PRICE_UNAVAILABLE.messageCode()).isEqualTo("MSG43");
                });
    }

    /**
     * Pricing does not need leverage brackets (T-085): with none stored, a Futures MARKET plan is priced at the mark
     * price and stops only at the bracket step, on the leverage field.
     */
    @Test
    void BR33_aFuturesMarketPlan_isPricedWithoutLeverageBrackets() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));
        when(market.leverageBrackets(PAIR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.calculate(USER, futuresMarket()))
                .satisfies(e -> assertFields(e, Map.of("leverage", "MSG15")));
        verify(market).currentMarkPrice("BTCUSDT");
    }

    /** Spot is unchanged: the last price, never the mark price. */
    @Test
    void BR33_aSpotMarketPlan_neverReadsAMarkPrice() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));

        service.calculate(USER, spotMarket("95", "110"));

        verify(market, never()).currentMarkPrice(any());
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
                null,
                null,
                null,
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
    void BR33_aMarketPlanActivated_isFilledAtOnce_andNeverWaitsInTheBooks() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));

        TradingPlanResponse plan = service.create(USER, activating(spotMarket("95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(saved.getExecutedAt()).isEqualTo(NOW);
        assertThat(saved.getActivatedAt()).isEqualTo(NOW);
        assertThat(saved.getFillPrice()).as("Q1: the last price, stored").isEqualByComparingTo("100");
        verify(events, never()).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void BR33_aLongLimitTheLastPriceAlreadyReached_isFilledAtActivation_belowAndExactlyAtTheEntry() {
        for (String last : new String[] {"99.5", "100"}) {
            when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(last)));

            TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

            assertThat(plan.status()).as("last price %s", last).isEqualTo(PlanStatus.EXECUTED);
            assertThat(saved.getExecutedAt()).isEqualTo(NOW);
            assertThat(saved.getFillPrice())
                    .as("Q1: the last price, not the limit price")
                    .isEqualByComparingTo(last);
        }
        verify(events, never()).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void BR33_aLongLimitAboveTheLastPrice_waitsForTheMatchingEngine() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.01")));

        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(saved.getExecutedAt()).isNull();
        assertThat(saved.getFillPrice()).isNull();
        verify(events).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void BR33_aLimitActivatedWithoutACurrentLastPrice_waitsForTheMatchingEngine() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        verify(events).publishEvent(any(TradingPlanActivated.class));
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
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));
        service.create(
                USER,
                create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, stop, takeProfit, null, null, 5));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal(lastPrice)));

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100");
    }

    /**
     * The draft was saved on a tick of 0.01; the pair's tick has since become 0.1 (NSF-01). Activation checks the
     * stored stop against the current tick and reports MSG15 on it, without rounding it to a price the Trader never
     * entered.
     */
    @Test
    void BR31_activateAfterATickSizeChange_isRefusedWithMsg15() {
        service.create(USER, spotLimit("100", "95.05", "110"));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        PairFilters coarser = new PairFilters(new BigDecimal("0.1"), new BigDecimal("0.001"), new BigDecimal("5"));
        when(market.tradablePair(eq(PAIR), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(PAIR, "BTCUSDT", call.getArgument(1), coarser)));

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG15")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getStopLoss()).isEqualByComparingTo("95.05");
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
                        EnumSet.of(PlanStatus.CANCELLED, PlanStatus.EXPIRED, PlanStatus.INVALIDATED),
                        EnumSet.of(MarketType.SPOT),
                        false,
                        PAIR,
                        from,
                        NOW,
                        PageRequest.of(1, 5));
    }

    /** CR-04: 1 and 100 are the ends of the page size range, both accepted. */
    @ParameterizedTest(name = "pageSize {0}")
    @ValueSource(ints = {1, 100})
    void CR04_thePageSizeAtEitherEndOfItsRange_isAccepted(int pageSize) {
        when(plans.search(any(), any(), any(), any(boolean.class), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, pageSize), 0));

        assertThat(service.list(USER, new PlanListQuery(null, null, null, null, null, 1, pageSize))
                        .pageSize())
                .isEqualTo(pageSize);
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
    // ------------------------------------------------------------------------------------------
    // Plan validity (TP-A): STOP entry, invalidation price, entry deviation limit, analysis timeframe
    // ------------------------------------------------------------------------------------------

    @Test
    void MSG01_aStopPlan_needsItsStopPrice() {
        assertThatThrownBy(() -> service.create(USER, spotStop(null, "95", "110")))
                .satisfies(e -> assertFields(e, Map.of("entryPrice", "MSG01")));
    }

    @Test
    void D71_aStopPlanWithoutAnExpiry_expiresInSevenDaysLikeALimit() {
        TradingPlanResponse plan = service.create(USER, spotStop("100", "95", "110"));

        assertThat(plan.entryType()).isEqualTo(EntryType.STOP);
        assertThat(plan.entryPrice()).isEqualByComparingTo("100");
        assertThat(plan.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void D71_aStopPlanWithAnExpiry_keepsIt() {
        Instant expiry = NOW.plus(Duration.ofDays(2));

        TradingPlanResponse plan = service.create(USER, withExpiry(spotStop("100", "95", "110"), expiry));

        assertThat(plan.expiresAt()).isEqualTo(expiry);
    }

    /** The trigger of a STOP is the matching engine's (TP-B): an untriggered STOP waits and is never filled at once. */
    @ParameterizedTest(name = "last price {0}")
    @ValueSource(strings = {"99", "99.99"})
    void BR33_anUntriggeredStopPlanActivated_waitsAndIsNeverFilledAtActivation(String last) {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(last)));

        TradingPlanResponse plan = service.create(USER, activating(spotStop("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(saved.getFillPrice()).isNull();
        verify(events)
                .publishEvent(new TradingPlanActivated(
                        saved.getId(),
                        MarketType.SPOT,
                        PAIR,
                        Direction.LONG,
                        EntryType.STOP,
                        saved.getEntryPrice(),
                        NOW));
    }

    /** D-112 (Q-72): equal to the stop counts as triggered; the plan is refused, never turned into a MARKET entry. */
    @ParameterizedTest(name = "{0} {1} stop 100 at last {2}")
    @CsvSource({"SPOT, LONG, 100", "SPOT, LONG, 101", "FUTURES, SHORT, 100", "FUTURES, SHORT, 99"})
    void BR33_aTriggeredStop_isRefusedOnItsEntryPriceAtSaveAndActivate_andNothingIsSaved(
            MarketType marketType, Direction direction, String last) {
        currentPrice(marketType, last);

        assertThatThrownBy(() -> service.create(USER, activating(stop(marketType, direction))))
                .satisfies(e -> assertFields(e, Map.of("entryPrice", "MSG16")));
        verify(plans, never()).save(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest(name = "{0} {1} stop 100 at last {2}")
    @CsvSource({"SPOT, LONG, 100", "SPOT, LONG, 101", "FUTURES, SHORT, 100", "FUTURES, SHORT, 99"})
    void BR33_aStopDraftTheLastPriceTriggeredSinceTheSave_isRefusedAtActivation_andStaysADraft(
            MarketType marketType, Direction direction, String last) {
        TradingPlan plan = savedDraft(stop(marketType, direction));
        currentPrice(marketType, last);

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .satisfies(e -> assertFields(e, Map.of("entryPrice", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(plans, Mockito.times(1)).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void BR33_anUntriggeredFuturesShortStop_isActivatedAndWaits() {
        TradingPlan plan = savedDraft(stop(MarketType.FUTURES, Direction.SHORT));
        currentPrice(MarketType.FUTURES, "100.01");

        assertThat(service.activate(USER, plan.getId()).status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.getFillPrice()).isNull();
        verify(events).publishEvent(any(TradingPlanActivated.class));
    }

    @Test
    void BR33_aStopPlan_isNotActivatedWithoutACurrentLastPrice_butIsSavedAsADraft() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        assertThat(service.create(USER, spotStop("100", "95", "110")).status()).isEqualTo(PlanStatus.DRAFT);
        assertThatThrownBy(() -> service.create(USER, activating(spotStop("100", "95", "110"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
    }

    /** The stop and the invalidation price are checked against one read of the last price. */
    @Test
    void BR33_BR68_activatingAStopWithAnInvalidationPrice_readsTheLastPriceOnce() {
        TradingPlan plan = savedDraft(withValidity(spotStop("100", "95", "110"), "98", null, null), "99");
        currentPrice(MarketType.SPOT, "99.5");
        Mockito.clearInvocations(market);

        assertThat(service.activate(USER, plan.getId()).status()).isEqualTo(PlanStatus.ACTIVE);
        verify(market, Mockito.times(1)).currentLastPrice(MarketType.SPOT, "BTCUSDT");
    }

    // ------------------------------------------------------------------------------------------
    // Check order (TP-A review F-04, F-05)
    // ------------------------------------------------------------------------------------------

    @Test
    void MSG43_editingAnActivePlan_isAStateErrorBeforeAnyCalculationOrPriceCheck() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));
        service.create(USER, activating(spotLimit("100", "95", "110")));
        TradingPlan plan = saved;
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());
        Mockito.clearInvocations(market, riskProfiles);

        assertThatThrownBy(() -> service.update(USER, plan.getId(), updateWithInvalidation("100.5")))
                .isInstanceOf(IllegalPlanStateException.class);
        verifyNoInteractions(market, riskProfiles);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIMIT", "STOP"})
    void MSG29_activatingAFuturesPlanWithoutTheFeature_isRefusedBeforeTheMissingLastPriceIsNoticed(
            EntryType entryType) {
        TradingPlan plan = savedDraft(
                withValidity(
                        create(PAIR, MarketType.FUTURES, Direction.LONG, entryType, "100", "95", "110", null, null, 2),
                        entryType == EntryType.LIMIT ? "102" : "98",
                        null,
                        null),
                entryType == EntryType.LIMIT ? "101" : "99");
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.empty());
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "FREE has no Futures", "PRO"))
                .when(entitlements)
                .requireFeature(USER, Feature.FUTURES_ANALYSIS);

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void MSG29_saveAndActivateAFuturesStopWithoutTheFeature_isRefusedBeforeAnyPriceRead() {
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "FREE has no Futures", "PRO"))
                .when(entitlements)
                .requireFeature(USER, Feature.FUTURES_ANALYSIS);

        assertThatThrownBy(() -> service.create(USER, activating(stop(MarketType.FUTURES, Direction.LONG))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED));
        verify(market, never()).currentLastPrice(any(), any());
    }

    // ------------------------------------------------------------------------------------------
    // The panel runs the save's checks (TP-A review F-03)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR08_thePanel_refusesATimeframeOutsideTheAnalysisTimeframes() {
        assertThatThrownBy(() -> service.calculate(USER, withValidity(spotLimit("100", "95", "110"), null, null, "5m")))
                .satisfies(e -> assertFields(e, Map.of("analysisTimeframe", "MSG15")));
    }

    @Test
    void BR69_thePanel_refusesADeviationLimitAboveTheBlockValue() {
        assertThatThrownBy(
                        () -> service.calculate(USER, withValidity(spotLimit("100", "95", "110"), null, "0.76", null)))
                .satisfies(e -> assertFields(e, Map.of("maxEntryDeviationR", "MSG15")));
    }

    @Test
    void BR68_thePanel_refusesAnInvalidationPriceOnTheWrongSide_andSavesNothing() {
        currentPrice(MarketType.SPOT, "101");

        assertThatThrownBy(
                        () -> service.calculate(USER, withValidity(spotLimit("100", "95", "110"), "100.5", null, null)))
                .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG16")));
        assertThat(service.calculate(USER, withValidity(spotLimit("100", "95", "110"), "102", null, null)))
                .isNotNull();
        verify(plans, never()).save(any());
    }

    /** The panel checks a save, not an activation: a triggered STOP is refused only when it is activated. */
    @Test
    void BR33_thePanel_doesNotRefuseATriggeredStop() {
        currentPrice(MarketType.SPOT, "101");

        assertThat(service.calculate(USER, activating(spotStop("100", "95", "110"))))
                .isNotNull();
    }

    // ------------------------------------------------------------------------------------------
    // The invalidation price on the tick (TP-A review F-06)
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"102.004, 102.00", "102.005, 102.01", "102.014, 102.01"})
    void BR30_theInvalidationPrice_isStoredRoundedToTheTick(String entered, String stored) {
        currentPrice(MarketType.SPOT, "101");

        TradingPlanResponse plan =
                service.create(USER, withValidity(spotLimit("100", "95", "110"), entered, null, null));

        assertThat(plan.invalidationPrice()).isEqualByComparingTo(stored);
    }

    @Test
    void BR30_theInvalidationPrice_isRoundedBeforeItIsChecked() {
        currentPrice(MarketType.SPOT, "101");

        assertThatThrownBy(
                        () -> service.create(USER, withValidity(spotLimit("100", "95", "110"), "101.004", null, null)))
                .as("101.004 rounds to the current price 101, which is not above it")
                .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG16")));
    }

    /** LIMIT LONG and STOP SHORT: above the current price; LIMIT SHORT and STOP LONG: below it. */
    @ParameterizedTest(name = "{1} {2} at {6}: invalidation {7} -> {8}")
    @CsvSource({
        "SPOT,    LONG,  LIMIT, 100, 95,  110, 101, 101.01, true",
        "SPOT,    LONG,  LIMIT, 100, 95,  110, 101, 101,    false",
        "SPOT,    LONG,  LIMIT, 100, 95,  110, 101, 100.99, false",
        "FUTURES, SHORT, LIMIT, 100, 105, 90,  99,  98.99,  true",
        "FUTURES, SHORT, LIMIT, 100, 105, 90,  99,  99,     false",
        "FUTURES, SHORT, LIMIT, 100, 105, 90,  99,  99.01,  false",
        "SPOT,    LONG,  STOP,  100, 95,  110, 99,  98.99,  true",
        "SPOT,    LONG,  STOP,  100, 95,  110, 99,  99,     false",
        "SPOT,    LONG,  STOP,  100, 95,  110, 99,  99.01,  false",
        "FUTURES, SHORT, STOP,  100, 105, 90,  101, 101.01, true",
        "FUTURES, SHORT, STOP,  100, 105, 90,  101, 101,    false",
        "FUTURES, SHORT, STOP,  100, 105, 90,  101, 100.99, false"
    })
    void BR68_theInvalidationPrice_mustLieOnTheFarSideOfTheCurrentPrice_elseMsg16OnItsField(
            MarketType marketType,
            Direction direction,
            EntryType entryType,
            String entry,
            String stop,
            String takeProfit,
            String current,
            String invalidation,
            boolean accepted) {
        currentPrice(marketType, current);
        CreateTradingPlanRequest request = withValidity(
                create(PAIR, marketType, direction, entryType, entry, stop, takeProfit, null, null, null),
                invalidation,
                null,
                null);

        if (accepted) {
            assertThat(service.create(USER, request).invalidationPrice()).isEqualByComparingTo(invalidation);
        } else {
            assertThatThrownBy(() -> service.create(USER, request))
                    .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG16")));
            verify(plans, never()).save(any());
        }
    }

    @Test
    void MSG01_aMarketPlan_takesNoInvalidationPrice() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));

        assertThatThrownBy(() -> service.create(USER, withValidity(spotMarket("95", "110"), "120", null, null)))
                .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG01")));
        verify(plans, never()).save(any());
    }

    @Test
    void BR68_anInvalidationPrice_isNotCheckedWithoutACurrentPrice_soTheSaveIsRefused() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER, withValidity(spotLimit("100", "95", "110"), "102", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
    }

    /** D-112: the last price decides, never the mark price, even when the mark price would accept it. */
    @Test
    void D112_theInvalidationPrice_isCheckedAgainstTheLastPrice_neverTheMarkPrice() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.empty());
        CreateTradingPlanRequest request = withValidity(
                create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, null, 2),
                "102",
                null,
                null);

        assertThatThrownBy(() -> service.create(USER, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));

        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("102.5")));
        assertThatThrownBy(() -> service.create(USER, request))
                .as("past the invalidation price by the last price, while the mark price is not")
                .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG16")));
    }

    @Test
    void BR68_aPlanWithoutAnInvalidationPrice_needsNoCurrentPriceToBeSaved() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        assertThat(service.create(USER, spotLimit("100", "95", "110")).status()).isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void BR68_atActivation_anInvalidationPriceThePriceHasReached_isMsg16AndThePlanStaysADraft() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));
        service.create(USER, withValidity(spotLimit("100", "95", "110"), "102", null, null));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("102")));

        assertThatThrownBy(() -> service.activate(USER, plan.getId()))
                .satisfies(e -> assertFields(e, Map.of("invalidationPrice", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(plans, Mockito.times(1)).save(any());
    }

    @Test
    void BR68_atActivation_anInvalidationPriceStillOnTheFarSide_isActivated() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));
        service.create(USER, withValidity(spotLimit("100", "95", "110"), "102", null, null));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        assertThat(service.activate(USER, plan.getId()).status()).isEqualTo(PlanStatus.ACTIVE);
    }

    @ParameterizedTest(name = "block {0}, plan {1} -> {2}")
    @CsvSource({"0.75, 0.75, true", "0.75, 0.1,  true", "0.75, 0.76, false", "0.5,  0.5,  true", "0.5,  0.6,  false"})
    void BR69_thePlansOwnDeviationLimit_isAtMostTheSystemBlockValue_elseMsg15(
            String block, String maxDeviation, boolean accepted) {
        when(settings.decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_BLOCK_R)).thenReturn(new BigDecimal(block));
        CreateTradingPlanRequest request = withValidity(spotLimit("100", "95", "110"), null, maxDeviation, null);

        if (accepted) {
            assertThat(service.create(USER, request).maxEntryDeviationR()).isEqualByComparingTo(maxDeviation);
        } else {
            assertThatThrownBy(() -> service.create(USER, request))
                    .satisfies(e -> assertFields(e, Map.of("maxEntryDeviationR", "MSG15")));
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"SCALPING, 15m", "DAY_TRADING, 1h", "SWING, 4h"})
    void D112_withoutATimeframe_thePlanTakesTheOneOfTheTradersPreset(SetupStyle style, String expected) {
        when(styles.styleOf(USER)).thenReturn(Optional.of(style));

        assertThat(service.create(USER, spotLimit("100", "95", "110")).analysisTimeframe())
                .isEqualTo(expected);
    }

    @Test
    void D112_withoutATimeframeOrAPreset_thePlanTakesOneHour() {
        when(styles.styleOf(USER)).thenReturn(Optional.empty());

        assertThat(service.create(USER, spotLimit("100", "95", "110")).analysisTimeframe())
                .isEqualTo("1h");
    }

    @Test
    void D112_withoutAProfileModule_thePlanTakesOneHour() {
        when(styleProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.create(USER, spotLimit("100", "95", "110")).analysisTimeframe())
                .isEqualTo("1h");
    }

    @Test
    void D112_aTimeframeTheTraderChose_isKept() {
        when(styles.styleOf(USER)).thenReturn(Optional.of(SetupStyle.SCALPING));

        assertThat(service.create(USER, withValidity(spotLimit("100", "95", "110"), null, null, "1d"))
                        .analysisTimeframe())
                .isEqualTo("1d");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5m", "1m", "1w", "1H", ""})
    void BR08_aTimeframeOutsideTheAnalysisTimeframes_isMsg15(String timeframe) {
        assertThatThrownBy(
                        () -> service.create(USER, withValidity(spotLimit("100", "95", "110"), null, null, timeframe)))
                .satisfies(e -> assertFields(e, Map.of("analysisTimeframe", "MSG15")));
    }

    @Test
    void BR69_thePlannedEntry_ofALimitOrStopPlan_isThePriceEntered() {
        assertThat(service.create(USER, spotLimit("100.004", "95", "110")).plannedEntryPrice())
                .as("rounded to the tick like the entry")
                .isEqualByComparingTo("100");
        assertThat(service.create(USER, spotStop("101", "95", "110")).plannedEntryPrice())
                .isEqualByComparingTo("101");
    }

    @Test
    void BR69_thePlannedEntry_ofAMarketPlan_isTheLastPriceOfTheSave_andFollowsEachSave() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));
        service.create(USER, spotMarket("95", "110"));
        TradingPlan plan = saved;
        assertThat(plan.getPlannedEntryPrice()).isEqualByComparingTo("100");
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));

        service.update(USER, plan.getId(), updateMarket("95", "110"));

        assertThat(plan.getPlannedEntryPrice()).isEqualByComparingTo("101");
    }

    @Test
    void BR69_activatingAMarketPlan_keepsThePlannedEntryOfTheLastSave() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));
        service.create(USER, spotMarket("95", "110"));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));

        service.activate(USER, plan.getId());

        assertThat(plan.getFillPrice()).isEqualByComparingTo("100.5");
        assertThat(plan.getPlannedEntryPrice()).isEqualByComparingTo("100");
    }

    @Test
    void BR36_aFillAtActivation_tookLiquidity_soItIsTaker() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));

        service.create(USER, activating(spotMarket("95", "110")));
        assertThat(saved.getFillLiquidity()).as("MARKET").isEqualTo(LiquidityRole.TAKER);

        service.create(USER, activating(spotLimit("100", "95", "110")));
        assertThat(saved.getFillLiquidity())
                .as("LIMIT marketable at activation")
                .isEqualTo(LiquidityRole.TAKER);
    }

    /** No public API of {@code market} gives the setup score of a (pair, market, timeframe, preset) yet (Q-74). */
    @Test
    void BR70_activation_recordsNoSetupScoreUntilMarketOffersOne_andIsNotBlocked() {
        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.setupScoreAtPlan()).isNull();
        assertThat(plan.setupScorePreset()).isNull();
    }

    private void currentPrice(MarketType marketType, String price) {
        when(market.currentLastPrice(marketType, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(price)));
    }

    /** A STOP at 100: LONG on Spot (SL 95, TP 110), SHORT on Futures (SL 105, TP 90, leverage 2). */
    private static CreateTradingPlanRequest stop(MarketType marketType, Direction direction) {
        return direction == Direction.LONG
                ? create(PAIR, marketType, direction, EntryType.STOP, "100", "95", "110", null, null, null)
                : create(PAIR, marketType, direction, EntryType.STOP, "100", "105", "90", null, null, 2);
    }

    /** A draft saved through the service while the last price of its market is {@code last}. */
    private TradingPlan savedDraft(CreateTradingPlanRequest request, String last) {
        currentPrice(request.market(), last);
        return savedDraft(request);
    }

    private TradingPlan savedDraft(CreateTradingPlanRequest request) {
        service.create(USER, request);
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        return plan;
    }

    private static UpdateTradingPlanRequest updateWithInvalidation(String invalidation) {
        return new UpdateTradingPlanRequest(
                MarketType.SPOT,
                Direction.LONG,
                EntryType.LIMIT,
                decimal("100"),
                decimal("95"),
                decimal("110"),
                null,
                null,
                null,
                null,
                null,
                decimal(invalidation),
                null,
                null,
                false);
    }

    private static CreateTradingPlanRequest spotStop(String entry, String stop, String takeProfit) {
        return create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.STOP, entry, stop, takeProfit, null, null, null);
    }

    private static CreateTradingPlanRequest withExpiry(CreateTradingPlanRequest r, Instant expiresAt) {
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
                expiresAt,
                r.note(),
                r.invalidationPrice(),
                r.maxEntryDeviationR(),
                r.analysisTimeframe(),
                r.activate());
    }

    private static CreateTradingPlanRequest withValidity(
            CreateTradingPlanRequest r, String invalidation, String maxDeviation, String timeframe) {
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
                decimal(invalidation),
                decimal(maxDeviation),
                timeframe,
                r.activate());
    }

    private static UpdateTradingPlanRequest updateMarket(String stop, String takeProfit) {
        return new UpdateTradingPlanRequest(
                MarketType.SPOT,
                Direction.LONG,
                EntryType.MARKET,
                null,
                decimal(stop),
                decimal(takeProfit),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

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

    private static CreateTradingPlanRequest futuresMarket() {
        return create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.MARKET, null, "95", "110", null, null, 5);
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
                null,
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
                r.invalidationPrice(),
                r.maxEntryDeviationR(),
                r.analysisTimeframe(),
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
                null,
                null,
                null,
                activate);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

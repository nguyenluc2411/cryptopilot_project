package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.PositionResponse;
import com.cryptopilot.trading.dto.response.StatusChangeResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.exception.EntryDeviationException;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.exception.PlanActivationBlockedException;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PlanTab;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.model.enums.WarningSeverity;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
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

    /** The pair of the BTC example of BR-69 (TD 7.8), with its step of 0.0001. */
    private static final UUID BTC = UUID.fromString("019b76da-a800-7000-8000-00000000b002");

    private static final PairFilters BTC_FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.0001"), new BigDecimal("5"));
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

    private final TradeJournal journal = mock(TradeJournal.class);

    private TradingPlanServiceImpl service;

    private final FundingService funding = mock(FundingService.class);

    /** The last plan the service saved. */
    private TradingPlan saved;

    @BeforeEach
    void setUp() {
        service = new TradingPlanServiceImpl(
                plans,
                new PlanCalculationAssembler(market, settings, new FeeRates(settings)),
                lock,
                market,
                riskProfiles,
                entitlements,
                Clock.fixed(NOW, ZoneOffset.UTC),
                events,
                settings,
                styleProvider,
                journal,
                new PositionOpener(journal, new FeeRates(settings), market),
                new PositionViews(journal, market, new FeeRates(settings), funding, settings));
        when(journal.openSimulatedTrade(any())).thenAnswer(call -> recorded(call.getArgument(0)));
        when(styleProvider.getIfAvailable()).thenReturn(styles);
        when(settings.decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_BLOCK_R)).thenReturn(new BigDecimal("0.75"));
        when(settings.decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_CONFIRM_R)).thenReturn(new BigDecimal("0.25"));
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
        when(market.tradablePair(eq(BTC), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(BTC, "BTCUSDT", call.getArgument(1), BTC_FILTERS)));
        List<LeverageTier> brackets = market.leverageBrackets(PAIR);
        when(market.leverageBrackets(BTC)).thenReturn(brackets);
        // The preset timeframes of the configuration (D-53); no style reads the default preset, DAY_TRADING.
        when(market.presetTimeframe(any())).thenAnswer(call -> switch ((SetupStyle) call.getArgument(0)) {
            case SCALPING -> "15m";
            case SWING -> "4h";
            case null, default -> "1h";
        });
        // No stored analysis unless a test stores one: the score is null and the preset is the one asked for.
        when(market.closedCandleSetupScore(any(), any(), anyString(), any(), any(), any()))
                .thenAnswer(call -> new SetupScoreReading(
                        null,
                        call.getArgument(3) == null ? SetupStyle.DAY_TRADING : call.getArgument(3),
                        "v1",
                        call.getArgument(2),
                        NOW.minusSeconds(3600)));
        when(settings.decimal(PlanCalculationAssembler.LOW_RR)).thenReturn(new BigDecimal("1.5"));
        when(settings.decimal(PlanCalculationAssembler.WIDE_STOP_LOSS)).thenReturn(BigDecimal.TEN);
        when(settings.decimal(PlanCalculationAssembler.HIGH_FUNDING_RATE)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(PlanCalculationAssembler.FEE_RISK_WARN_RATIO)).thenReturn(new BigDecimal("0.2"));
        when(settings.decimal(FeeRates.SPOT_MAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(FeeRates.SPOT_TAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(0);
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, new BigDecimal("1000"), null));
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(BigDecimal.ZERO);
        when(plans.save(any())).thenAnswer(call -> {
            saved = call.getArgument(0);
            return saved;
        });
        when(plans.saveAndFlush(any())).thenAnswer(call -> {
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
    void D121_theRiskPercentEntered_winsOverTheDefault_theCapitalIsAlwaysTheProfiles() {
        CreateTradingPlanRequest request =
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", "2", null);

        PlanCalculationResponse panel = service.calculate(USER, request);

        assertThat(panel.capital()).isEqualByComparingTo("1000");
        assertThat(panel.riskPercent()).isEqualByComparingTo("2");
        assertThat(panel.snapshot().positionQuantity())
                .as("2 % of the profile's 1,000 over a stop of 5")
                .isEqualByComparingTo("4");
    }

    /** D-121: a profile without a default capital above 0 refuses the panel on {@code capital} (MSG01). */
    @ParameterizedTest(name = "default capital {0}")
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void MSG01_withoutADefaultCapitalAbove0_thePanelIsRefusedOnTheCapital(String capital) {
        profileCapital(capital);

        assertThatThrownBy(() -> service.calculate(USER, spotLimit("100", "95", "110")))
                .satisfies(e -> assertFields(e, Map.of("capital", "MSG01")));
    }

    /** D-121: the smallest default capital above 0 is a capital, not a missing one. */
    @Test
    void D121_aDefaultCapitalJustAbove0_isUsedAsTheCapital() {
        profileCapital("0.00000001");

        assertThatThrownBy(() -> service.calculate(USER, spotLimit("100", "95", "110")))
                .as("accepted as the capital, then too small to buy one step (MSG15), not refused as missing")
                .satisfies(e -> assertFields(e, Map.of("quantity", "MSG15")));
    }

    /** D-121: create, update and activate are refused the same way, and nothing is saved. */
    @Test
    void MSG01_withoutADefaultCapital_createUpdateAndActivateAreRefusedOnTheCapital() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        saved = null;
        profileCapital(null);

        assertThatThrownBy(() -> service.create(USER, spotLimit("100", "95", "110")))
                .satisfies(e -> assertFields(e, Map.of("capital", "MSG01")));
        assertThatThrownBy(() -> service.update(USER, plan.getId(), update("100", "96", "120", false)))
                .satisfies(e -> assertFields(e, Map.of("capital", "MSG01")));
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("capital", "MSG01")));
        assertThat(saved).isNull();
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getCapital()).isEqualByComparingTo("1000");
    }

    /**
     * D-121, BR-31: a draft keeps the capital of its save while the profile changes; its activation recalculates the
     * plan on the profile's capital of now and stores it with the new size.
     */
    @Test
    void BR31_activation_recalculatesOnTheProfileCapitalOfNow_andStoresIt() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        profileCapital("3000");

        assertThat(service.get(USER, plan.getId()).capital())
                .as("the stored snapshot does not follow the profile")
                .isEqualByComparingTo("1000");
        TradingPlanResponse active = service.activate(USER, plan.getId(), false);

        assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(active.capital()).isEqualByComparingTo("3000");
        assertThat(active.snapshot().positionQuantity()).isEqualByComparingTo("6");
        assertThat(active.snapshot().riskAmount()).isEqualByComparingTo("30");
        assertThat(plan.getCapital()).isEqualByComparingTo("3000");
    }

    /**
     * D-121, BR-25 and BR-26 at activation (BR-31): 10 % risk over a stop of 9.99 is a notional just above the capital
     * unless the step floors it back. On 50 the quantity 0.5005 floors to 0.500, a notional (Futures at 1x: a margin)
     * of 50, equal to the capital: no warning. On 1,000 the quantity 10.01001 floors to 10.010, a notional of 1,001,
     * above the capital: BLOCKING INSUFFICIENT_CAPITAL. A draft saved under one profile capital is checked again
     * under the other one.
     */
    @ParameterizedTest(name = "{0}: saved on {1}, activated on {2}")
    @CsvSource({
        "SPOT,    50,   1000, true",
        "SPOT,    1000, 50,   false",
        "FUTURES, 50,   1000, true",
        "FUTURES, 1000, 50,   false"
    })
    void BR25_BR26_activation_checksTheCapitalAgainstTheProfileCapitalOfNow(
            MarketType marketType, String savedOn, String activatedOn, boolean blockedAtActivation) {
        profileCapital(savedOn);
        CreateTradingPlanRequest request = create(
                PAIR,
                marketType,
                Direction.LONG,
                EntryType.LIMIT,
                "100",
                "90.01",
                "130",
                "10",
                marketType == MarketType.FUTURES ? 1 : null);
        TradingPlanResponse draft = service.create(USER, request);
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        assertThat(draft.blocksActivation()).isEqualTo(!blockedAtActivation);
        profileCapital(activatedOn);

        if (blockedAtActivation) {
            assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                    .isInstanceOf(PlanActivationBlockedException.class);
            assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
            assertThat(plan.getCapital()).isEqualByComparingTo(savedOn);
        } else {
            TradingPlanResponse active = service.activate(USER, plan.getId(), false);
            assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
            assertThat(active.capital()).isEqualByComparingTo(activatedOn);
            assertThat(active.warnings())
                    .extracting(PlanWarningResponse::type)
                    .doesNotContain(WarningType.INSUFFICIENT_CAPITAL);
        }
    }

    /**
     * D-121, BR-29: the other open risk, in USDT, is a share of the profile's capital of now. 30 of other risk plus
     * this plan's 10 is 4 % of 1,000, not above BALANCED's 4 %; plus 20 on 2,000 it is 2.5 %; on 500, where this plan
     * risks 5, 7 %.
     */
    @ParameterizedTest(name = "capital {0}: {1} %")
    @CsvSource({"1000, 4, false", "2000, 2.5, false", "500, 7, true"})
    void BR29_theTotalOpenRisk_isAShareOfTheProfileCapitalOfNow(String capital, String percent, boolean warned) {
        profileCapital(capital);
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("30"));

        PlanCalculationResponse panel = service.calculate(USER, spotLimit("100", "95", "110"));

        List<PlanWarningResponse> totalOpenRisk = panel.warnings().stream()
                .filter(w -> w.type() == WarningType.TOTAL_OPEN_RISK)
                .toList();
        if (warned) {
            assertThat(totalOpenRisk)
                    .singleElement()
                    .extracting(PlanWarningResponse::message)
                    .asString()
                    .startsWith("the total open risk of " + percent + "% of capital");
        } else {
            assertThat(totalOpenRisk).isEmpty();
        }
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

    /**
     * D-111 E2/E3: a Futures MARKET plan is priced at the Futures last price, the close of the latest 1-minute candle
     * update, never at the mark price, here cached at another value.
     */
    @Test
    void D111_aFuturesMarketPlan_isCalculatedAtTheLastPrice_notTheMarkPrice() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.2")));

        PlanCalculationResponse panel = service.calculate(USER, futuresMarket());

        assertThat(panel.entryPrice()).isEqualByComparingTo("100.2");
        verify(market, never()).currentMarkPrice(any());
    }

    /** No current Futures last price: the existing 503 MSG43 path, even with a current mark price. */
    @Test
    void BR33_withoutACurrentLastPrice_aFuturesMarketPlanCannotBeCalculated() {
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.empty());
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
     * Pricing does not need leverage brackets (T-085): with none stored, a Futures MARKET plan is priced at the last
     * price and stops only at the bracket step, on the leverage field.
     */
    @Test
    void BR33_aFuturesMarketPlan_isPricedWithoutLeverageBrackets() {
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100.5")));
        when(market.leverageBrackets(PAIR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.calculate(USER, futuresMarket()))
                .satisfies(e -> assertFields(e, Map.of("leverage", "MSG15")));
        verify(market).currentLastPrice(MarketType.FUTURES, "BTCUSDT");
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
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, 3);

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
                expiry,
                "note",
                null,
                null,
                null,
                false,
                null);

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
                        USER,
                        MarketType.SPOT,
                        PAIR,
                        Direction.LONG,
                        EntryType.LIMIT,
                        saved.getEntryPrice(),
                        saved.getStopLoss(),
                        saved.getTakeProfit(),
                        null,
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
        profileCapital("100");
        CreateTradingPlanRequest blocked = activating(
                create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "95", "110", "10", null));

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
        assertThatThrownBy(() -> service.activate(USER, planId, false)).isInstanceOf(ResourceNotFoundException.class);
        when(plans.findForUpdate(planId, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(USER, planId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void UC18_activation_locksCountsAndRecalculatesInThatOrder() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(plans.countActive(USER)).thenReturn(1L);

        TradingPlanResponse active = service.activate(USER, plan.getId(), false);

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

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOf(IllegalPlanStateException.class);
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

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
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
                USER, create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, stop, takeProfit, null, 5));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(lastPrice)));

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
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

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
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
    // The position of a filled plan (T-048a: BR-34, BR-62, BR-29, UC-20)
    // ------------------------------------------------------------------------------------------

    /**
     * BR-34: a Spot MARKET filled at activation (last 100, quantity 2) is written at once and its OPEN position recorded
     * after it, in the same transaction: fill price and instant, the snapshot quantity, TAKER, the entry fee 100 × 2 ×
     * 0.1 % = 0.2 at the stored scale, and the levels it starts with.
     */
    @Test
    void BR34_aPlanFilledAtActivation_isWrittenAtOnce_andItsPositionOpenedWithTheTakerFee() {
        currentPrice(MarketType.SPOT, "100");

        service.create(USER, activating(spotMarket("95", "110")));

        ArgumentCaptor<SimulatedTradeOpening> opening = ArgumentCaptor.forClass(SimulatedTradeOpening.class);
        InOrder order = inOrder(plans, journal);
        order.verify(plans).saveAndFlush(saved);
        order.verify(journal).openSimulatedTrade(opening.capture());
        verify(plans, never()).save(any());
        SimulatedTradeOpening opened = opening.getValue();
        assertThat(opened.planId()).isEqualTo(saved.getId());
        assertThat(opened.userId()).isEqualTo(USER);
        assertThat(opened.pairId()).isEqualTo(PAIR);
        assertThat(opened.market()).isEqualTo(MarketType.SPOT);
        assertThat(opened.direction()).isEqualTo(Direction.LONG);
        assertThat(opened.leverage()).isNull();
        assertThat(opened.entryPrice()).isEqualByComparingTo("100");
        assertThat(opened.entryTime()).isEqualTo(NOW);
        assertThat(opened.quantity()).isEqualByComparingTo("2");
        assertThat(opened.entryLiquidity()).isEqualTo(LiquidityRole.TAKER);
        assertThat(opened.entryFee()).isEqualTo(new BigDecimal("0.20000000"));
        assertThat(opened.stopLoss()).isEqualByComparingTo("95");
        assertThat(opened.takeProfit()).isEqualByComparingTo("110");
    }

    /** BR-34: a Futures fill pays the Futures taker rate, 100 × 2 × 0.05 % = 0.1, and keeps its leverage. */
    @Test
    void BR34_aFuturesFillAtActivation_paysTheFuturesTakerRate() {
        currentPrice(MarketType.FUTURES, "100");

        service.create(USER, activating(futuresMarket()));

        ArgumentCaptor<SimulatedTradeOpening> opening = ArgumentCaptor.forClass(SimulatedTradeOpening.class);
        verify(journal).openSimulatedTrade(opening.capture());
        assertThat(opening.getValue().entryFee()).isEqualTo(new BigDecimal("0.10000000"));
        assertThat(opening.getValue().leverage()).isEqualTo(5);
        assertThat(opening.getValue().market()).isEqualTo(MarketType.FUTURES);
    }

    /** BR-34: a plan that waits for the engine, and a draft, are saved as before and open no position. */
    @Test
    void BR34_aPlanThatWaitsOrADraft_opensNoPosition() {
        service.create(USER, activating(spotLimit("100", "95", "110")));
        service.create(USER, spotLimit("100", "95", "110"));

        verify(plans, never()).saveAndFlush(any());
        verify(journal, never()).openSimulatedTrade(any());
    }

    /**
     * BR-62, D-63: an open position keeps its {@code ACTIVE_PLAN_MAX} slot: one ACTIVE plan and two open positions count
     * 3, read under the lock, the ACTIVE plans first (D-119).
     */
    @Test
    void BR62_openPositions_countAgainstActivePlanMax_afterTheActivePlans() {
        when(plans.countActive(USER)).thenReturn(1L);
        when(journal.openSimulatedTrades(USER))
                .thenReturn(List.of(openTrade("100", "100", "1"), openTrade("100", "100", "1")));

        service.create(USER, activating(spotLimit("100", "95", "110")));

        InOrder order = inOrder(lock, plans, journal, entitlements);
        order.verify(lock).lock(USER);
        order.verify(plans).countActive(USER);
        order.verify(journal).openSimulatedTrades(USER);
        order.verify(entitlements).requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 3L);
    }

    /**
     * BR-29, D-124: an open position adds its CURRENT risk to TOTAL_OPEN_RISK, quantity × (entry − current stop loss).
     * The plan risks 10; with 20 in ACTIVE plans and an open LONG of 1 at 100 stopped at 85 (15) the total is 4.5 % of
     * 1,000, above BALANCED's 4 %.
     */
    @Test
    void BR29_theCurrentRiskOfOpenPositions_raisesTotalOpenRisk() {
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("20"));
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(openTrade("100", "85", "1")));

        assertThat(service.calculate(USER, spotLimit("100", "95", "110")).warnings())
                .extracting(PlanWarningResponse::type, PlanWarningResponse::severity)
                .contains(org.assertj.core.groups.Tuple.tuple(WarningType.TOTAL_OPEN_RISK, WarningSeverity.WARNING));
    }

    /**
     * BR-29, D-124: an open position whose stop loss was moved to its entry carries no risk: 20 in ACTIVE plans and the
     * plan's 10 stay at 3 % of 1,000, under the 4 % maximum, whatever the plan's own risk at its fill was.
     */
    @Test
    void BR29_anOpenPositionStoppedAtItsEntry_addsNoOpenRisk() {
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("20"));
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(openTrade("100", "100", "1")));

        assertThat(service.calculate(USER, spotLimit("100", "95", "110")).warnings())
                .extracting(PlanWarningResponse::type)
                .doesNotContain(WarningType.TOTAL_OPEN_RISK);
    }

    /** BR-29, D-124: TOTAL_OPEN_RISK sums the current risk of every open position, LONG below, SHORT above the entry. */
    @Test
    void BR29_currentRisk_sumsTheLosingSideOfEveryStop() {
        assertThat(TradingPlanServiceImpl.currentRisk(List.of(
                        openTrade("100", "90", "2"),
                        openTrade("100", "105", "2"),
                        openTrade(Direction.SHORT, "100", "104", "3"),
                        openTrade(Direction.SHORT, "100", "95", "3"))))
                .as("LONG (100 − 90) × 2 = 20, a LONG stop above the entry 0, SHORT (104 − 100) × 3 = 12, 0")
                .isEqualByComparingTo("32");
    }

    /** BR-29: without an open position no plan risk is read, and 20 in ACTIVE plans stays under the 4 % maximum. */
    @Test
    void BR29_withoutOpenPositions_noPlanRiskIsRead() {
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("20"));

        assertThat(service.calculate(USER, spotLimit("100", "95", "110")).warnings())
                .extracting(PlanWarningResponse::type)
                .doesNotContain(WarningType.TOTAL_OPEN_RISK);
        verify(plans, never()).riskOf(any());
    }

    /**
     * BR-29, BR-62, D-119: an activation reads the open positions once, under the lock and after the plan table, and
     * both checks use that read: 1 ACTIVE plan and 1 open position fill 2 slots, and the position's 15 with the 20 in
     * ACTIVE plans is 3.5 % of 1,000, which with the plan's 10 raises TOTAL_OPEN_RISK.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"create", "update", "activate"})
    void BR62_anActivation_readsTheOpenPositionsOnceForBothChecks(String path) {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(plans.countActive(USER)).thenReturn(1L);
        when(plans.activeRiskExcluding(eq(USER), any())).thenReturn(new BigDecimal("20"));
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(openTrade("100", "85", "1")));
        Mockito.clearInvocations(plans, journal);

        TradingPlanResponse active =
                switch (path) {
                    case "create" -> service.create(USER, activating(spotLimit("100", "95", "110")));
                    case "update" -> service.update(USER, plan.getId(), update("100", "95", "110", true));
                    default -> service.activate(USER, plan.getId(), false);
                };

        assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(active.warnings()).extracting(PlanWarningResponse::type).contains(WarningType.TOTAL_OPEN_RISK);
        verify(journal, Mockito.times(1)).openSimulatedTrades(USER);
        InOrder order = inOrder(lock, plans, journal, entitlements);
        order.verify(lock).lock(USER);
        order.verify(plans).countActive(USER);
        order.verify(plans).activeRiskExcluding(eq(USER), any());
        order.verify(journal).openSimulatedTrades(USER);
        order.verify(entitlements).requireWithinLimit(USER, Feature.ACTIVE_PLAN_MAX, 2L);
    }

    /**
     * BR-29, D-119: a calculation and a draft save activate nothing, so they count no ACTIVE plan and check no
     * {@code ACTIVE_PLAN_MAX}; each still reads the open positions once, after the ACTIVE plans' risk.
     */
    @Test
    void BR29_aCalculationOrADraftSave_countsNoActivePlanAndReadsTheJournalOnce() {
        service.calculate(USER, spotLimit("100", "95", "110"));
        service.create(USER, spotLimit("100", "95", "110"));

        verify(plans, never()).countActive(any());
        verify(entitlements, never()).requireWithinLimit(any(), eq(Feature.ACTIVE_PLAN_MAX), anyLong());
        verify(journal, Mockito.times(2)).openSimulatedTrades(USER);
        InOrder order = inOrder(plans, journal);
        order.verify(plans).activeRiskExcluding(eq(USER), any());
        order.verify(journal).openSimulatedTrades(USER);
    }

    /**
     * UC-20, Q-55: an open Spot position is valued at the Spot last price, without fees: (103 − 100) × 2 = 6. Spot has
     * no liquidation price.
     */
    @Test
    void UC20_anOpenSpotPosition_answersItsUnrealizedPnlAtTheLastPrice() {
        TradingPlan plan = executedSpotMarket();
        position(plan, PositionStatus.OPEN);
        when(market.pairListings(List.of(PAIR))).thenReturn(List.of(new PairListing(PAIR, "BTCUSDT", true, true)));
        currentPrice(MarketType.SPOT, "103");

        PositionResponse position = service.get(USER, plan.getId()).position();

        assertThat(position.status()).isEqualTo(PositionStatus.OPEN);
        assertThat(position.fillPrice()).isEqualByComparingTo("100");
        assertThat(position.quantity()).isEqualByComparingTo("2");
        assertThat(position.entryTime()).isEqualTo(NOW);
        assertThat(position.entryFee()).isEqualByComparingTo("0.2");
        assertThat(position.liquidationPrice()).isNull();
        assertThat(position.currentPrice()).isEqualByComparingTo("103");
        assertThat(position.unrealizedPnl()).isEqualTo(new BigDecimal("6.00000000"));
    }

    /**
     * UC-20, Q-55: an open Futures position is valued at the mark price, not the last price: (99 − 100) × 2 = −2, and
     * answers the liquidation price the journal holds for it.
     */
    @Test
    void UC20_anOpenFuturesPosition_isValuedAtTheMarkPrice_andAnswersItsLiquidationPrice() {
        currentPrice(MarketType.FUTURES, "100");
        service.create(USER, activating(futuresMarket()));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        position(plan, PositionStatus.OPEN);
        when(market.pairListings(List.of(PAIR))).thenReturn(List.of(new PairListing(PAIR, "BTCUSDT", true, true)));
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("99")));
        when(funding.fundingSoFar(eq(PAIR), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));

        PositionResponse position = service.get(USER, plan.getId()).position();

        assertThat(position.currentPrice()).isEqualByComparingTo("99");
        assertThat(position.unrealizedPnl()).isEqualTo(new BigDecimal("-2.00000000"));
        assertThat(position.liquidationPrice())
                .as("the journal's liquidation price at the fill, not the plan's estimate")
                .isEqualByComparingTo("88.88");
    }

    /** Q-55: without a cached price an open position answers no current price and no unrealized P/L. */
    @Test
    void UC20_anOpenPositionWithoutACachedPrice_answersNoUnrealizedPnl() {
        TradingPlan plan = executedSpotMarket();
        position(plan, PositionStatus.OPEN);
        when(market.pairListings(List.of(PAIR))).thenReturn(List.of(new PairListing(PAIR, "BTCUSDT", true, true)));
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        PositionResponse position = service.get(USER, plan.getId()).position();

        assertThat(position.status()).isEqualTo(PositionStatus.OPEN);
        assertThat(position.currentPrice()).isNull();
        assertThat(position.unrealizedPnl()).isNull();
    }

    /**
     * UC-20, BR-38: a closed position answers its entry and exit figures and the net P/L of the stored amounts, final
     * on Spot, and reads no price at all.
     */
    @Test
    void UC20_aClosedPosition_answersItsExitAndNet_andReadsNoPrice() {
        TradingPlan plan = executedSpotMarket();
        position(plan, PositionStatus.CLOSED);

        PositionResponse position = service.get(USER, plan.getId()).position();

        assertThat(position.status()).isEqualTo(PositionStatus.CLOSED);
        assertThat(position.currentPrice()).isNull();
        assertThat(position.unrealizedPnl()).isNull();
        assertThat(position.exitPrice()).isEqualByComparingTo("110");
        assertThat(position.exitTime()).isEqualTo(NOW);
        assertThat(position.closeReason()).isEqualTo(CloseReason.TAKE_PROFIT);
        assertThat(position.realizedPnl()).isEqualByComparingTo("20");
        assertThat(position.feeAmount()).isEqualByComparingTo("0.42");
        assertThat(position.fundingFeeAmount()).isNull();
        assertThat(position.netPnl()).isEqualByComparingTo("19.58");
        assertThat(position.provisional()).isFalse();
        verify(market, never()).pairListings(any());
    }

    /** UC-20: an open position has no exit, no amounts and no net yet. */
    @Test
    void UC20_anOpenPosition_hasNoExitYet() {
        TradingPlan plan = executedSpotMarket();
        position(plan, PositionStatus.OPEN);

        PositionResponse position = service.get(USER, plan.getId()).position();

        assertThat(position.exitPrice()).isNull();
        assertThat(position.closeReason()).isNull();
        assertThat(position.netPnl()).isNull();
        assertThat(position.provisional()).isFalse();
    }

    /** UC-20: a plan that is not EXECUTED has no position and does not ask the journal. */
    @Test
    void UC20_aPlanNotExecuted_hasNoPosition() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));

        assertThat(service.get(USER, plan.getId()).position()).isNull();
        verify(journal, never()).positionOf(any());
    }

    /**
     * SRS 3.5.2, BR-34: the list asks the journal once, for the page's EXECUTED plans only, and each row answers its
     * position status; a row that is not EXECUTED answers none.
     */
    @Test
    void SCR16_theExecutedRows_answerTheirPositionStatus() {
        TradingPlan executed = executedSpotMarket();
        TradingPlan waiting = draft();
        when(plans.search(any(), any(), any(), any(boolean.class), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(executed, waiting), PageRequest.of(0, 20), 2));
        when(journal.positionStatuses(List.of(executed.getId())))
                .thenReturn(Map.of(executed.getId(), PositionStatus.OPEN));

        PageResponse<TradingPlanSummaryResponse> page =
                service.list(USER, new PlanListQuery(null, null, null, null, null, 1, 20));

        assertThat(page.items())
                .extracting(TradingPlanSummaryResponse::positionStatus)
                .containsExactly(PositionStatus.OPEN, null);
    }

    /** SRS 3.5.2: a page without an EXECUTED plan does not ask the journal. */
    @Test
    void SCR16_aPageWithoutExecutedPlans_doesNotAskTheJournal() {
        TradingPlan waiting = draft();
        when(plans.search(any(), any(), any(), any(boolean.class), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(waiting), PageRequest.of(0, 20), 1));

        service.list(USER, new PlanListQuery(null, null, null, null, null, 1, 20));

        verify(journal, never()).positionStatuses(any());
    }

    /** A Spot MARKET filled at activation at 100 (quantity 2), readable by its owner. */
    private TradingPlan executedSpotMarket() {
        currentPrice(MarketType.SPOT, "100");
        service.create(USER, activating(spotMarket("95", "110")));
        TradingPlan plan = saved;
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        return plan;
    }

    /** The journal holds the plan's position: filled at 100, quantity 2, at {@code NOW}, entry fee 0.2. */
    private void position(TradingPlan plan, PositionStatus status) {
        when(journal.positionOf(plan.getId()))
                .thenReturn(Optional.of(new RecordedPosition(
                        plan.getId(),
                        status,
                        new BigDecimal("100"),
                        new BigDecimal("2"),
                        NOW,
                        new BigDecimal("0.2"),
                        plan.getMarket() == MarketType.FUTURES ? new BigDecimal("88.88") : null,
                        status == PositionStatus.CLOSED ? new BigDecimal("110") : null,
                        status == PositionStatus.CLOSED ? NOW : null,
                        status == PositionStatus.CLOSED ? CloseReason.TAKE_PROFIT : null,
                        status == PositionStatus.CLOSED ? new BigDecimal("20") : null,
                        status == PositionStatus.CLOSED ? new BigDecimal("0.42") : null,
                        null,
                        false)));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The journal's answer to an opening: the same values, a new id, version 0. */
    private static OpenTrade recorded(SimulatedTradeOpening opening) {
        return new OpenTrade(
                UUID.randomUUID(),
                opening.planId(),
                opening.userId(),
                opening.pairId(),
                opening.market(),
                opening.direction(),
                opening.entryPrice(),
                opening.entryTime(),
                opening.quantity(),
                opening.entryLiquidity(),
                opening.entryFee(),
                opening.stopLoss(),
                opening.takeProfit(),
                opening.liquidationPrice(),
                0);
    }

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
                        USER,
                        MarketType.SPOT,
                        PAIR,
                        Direction.LONG,
                        EntryType.STOP,
                        saved.getEntryPrice(),
                        saved.getStopLoss(),
                        saved.getTakeProfit(),
                        null,
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

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("entryPrice", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(plans, Mockito.times(1)).save(any());
        verifyNoInteractions(events);
    }

    /**
     * D-112 (Q-72): a STOP whose stop loss the last price has reached, at the level or past it, is refused on the stop
     * loss for Save and Activate and for Activate; the draft is unchanged and nothing is published.
     */
    @ParameterizedTest(name = "{0} {1} stop 100 at last {2}")
    @CsvSource({"SPOT, LONG, 95", "SPOT, LONG, 94", "FUTURES, SHORT, 105", "FUTURES, SHORT, 106"})
    void BR68_aStopWhoseStopLossIsReached_isRefusedOnTheStopLoss_atSaveAndActivateAndAtActivate(
            MarketType marketType, Direction direction, String last) {
        currentPrice(marketType, last);

        assertThatThrownBy(() -> service.create(USER, activating(stop(marketType, direction))))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        verify(plans, never()).save(any());

        TradingPlan plan = savedDraft(stop(marketType, direction));
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(events, never()).publishEvent(any(TradingPlanActivated.class));
    }

    /**
     * D-123 (team lead, 2026-10-25): a LIMIT whose stop loss the last price has reached, at the level or past it, is
     * refused on the stop loss for Save and Activate and for Activate, instead of filling a position already past its
     * stop; Futures reads the last price, never the mark price.
     */
    @ParameterizedTest(name = "{0} {1} limit 100 at last {2}")
    @CsvSource({"SPOT, LONG, 95", "SPOT, LONG, 94", "FUTURES, SHORT, 105", "FUTURES, SHORT, 106"})
    void D123_aLimitWhoseStopLossIsReached_isRefusedOnTheStopLoss_atSaveAndActivateAndAtActivate(
            MarketType marketType, Direction direction, String last) {
        currentPrice(marketType, last);

        assertThatThrownBy(() -> service.create(USER, activating(limit(marketType, direction))))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        verify(plans, never()).save(any());

        TradingPlan plan = savedDraft(limit(marketType, direction));
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(events, never()).publishEvent(any(TradingPlanActivated.class));
        verify(journal, never()).openSimulatedTrade(any());
        verify(market, never()).currentMarkPrice(any());
    }

    /** D-123: a LIMIT short of its stop loss still fills at once when the last price reaches its entry. */
    @ParameterizedTest(name = "{0} {1} limit 100 at last {2}")
    @CsvSource({"SPOT, LONG, 95.01", "FUTURES, SHORT, 104.99"})
    void D123_aLimitShortOfItsStopLoss_isActivatedAndFillsAtOnce(
            MarketType marketType, Direction direction, String last) {
        currentPrice(marketType, last);

        service.create(USER, activating(limit(marketType, direction)));

        assertThat(saved.getStatus()).isEqualTo(PlanStatus.EXECUTED);
    }

    /**
     * D-123: a MARKET whose stop loss the unslipped last price has reached, at the level or past it, is refused on the
     * stop loss, also when the slippage would move its fill back over the stop (100 bps: LONG last 95 would buy at
     * 95.95, above the stop of 95), for Save and Activate and for Activate; the stop distance of the deviation is
     * never read at zero.
     */
    @ParameterizedTest(name = "{0} stop {1} at last {2}")
    @CsvSource({"LONG, 95, 110, 95", "LONG, 95, 110, 94.5", "SHORT, 105, 90, 105", "SHORT, 105, 90, 105.5"})
    void D123_aMarketWhoseStopLossIsReached_isRefusedOnTheStopLoss_evenWhenTheSlippageCrossesItBack(
            Direction direction, String stop, String takeProfit, String last) {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(100);
        CreateTradingPlanRequest market =
                create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, stop, takeProfit, null, 5);
        currentPrice(MarketType.FUTURES, last);

        assertThatThrownBy(() -> service.create(USER, activating(market)))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        verify(plans, never()).save(any());

        TradingPlan plan = savedDraft(market, "100");
        currentPrice(MarketType.FUTURES, last);
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(journal, never()).openSimulatedTrade(any());
        verify(this.market, never()).currentMarkPrice(any());
    }

    /**
     * D-112 (Q-72): a LIMIT whose take profit the last price has reached, at the level or past it, is refused on the
     * take profit for Save and Activate and for Activate; the draft is unchanged and nothing is published.
     */
    @ParameterizedTest(name = "{0} {1} limit 100 at last {2}")
    @CsvSource({"SPOT, LONG, 110", "SPOT, LONG, 111", "FUTURES, SHORT, 90", "FUTURES, SHORT, 89"})
    void BR68_aLimitWhoseTakeProfitIsReached_isRefusedOnTheTakeProfit_atSaveAndActivateAndAtActivate(
            MarketType marketType, Direction direction, String last) {
        currentPrice(marketType, last);

        assertThatThrownBy(() -> service.create(USER, activating(limit(marketType, direction))))
                .satisfies(e -> assertFields(e, Map.of("takeProfit", "MSG16")));
        verify(plans, never()).save(any());

        TradingPlan plan = savedDraft(limit(marketType, direction));
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .satisfies(e -> assertFields(e, Map.of("takeProfit", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        verify(events, never()).publishEvent(any(TradingPlanActivated.class));
    }

    /** D-112: a LIMIT short of its take profit, not reached by the last price, is activated and waits. */
    @ParameterizedTest(name = "{0} {1} limit 100 at last {2}")
    @CsvSource({"SPOT, LONG, 109.99", "FUTURES, SHORT, 90.01"})
    void BR68_aLimitShortOfItsTakeProfit_isActivatedAndWaits(MarketType marketType, Direction direction, String last) {
        TradingPlan plan = savedDraft(limit(marketType, direction));
        currentPrice(marketType, last);

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.getFillPrice()).isNull();
        verify(events).publishEvent(any(TradingPlanActivated.class));
    }

    /**
     * D-112: the activation refusals read the Futures last price, never the mark price. A mark price past the level
     * does not refuse a plan the last price leaves untouched, and a mark price short of it does not let through one
     * the last price has reached.
     */
    @ParameterizedTest(name = "{0} at mark {1}, last {2} -> refused on {3}")
    @CsvSource({
        "STOP,  104.5, 106,   stopLoss",
        "STOP,  106,   104.5, ''",
        "LIMIT, 89,    91,    ''",
        "LIMIT, 91,    89,    takeProfit"
    })
    void D112_theActivationRefusals_readTheLastPrice_neverTheMarkPrice(
            EntryType entryType, String mark, String last, String refusedField) {
        // A Futures SHORT at 100: STOP with stop loss 105, LIMIT with take profit 90.
        CreateTradingPlanRequest request = entryType == EntryType.STOP
                ? stop(MarketType.FUTURES, Direction.SHORT)
                : limit(MarketType.FUTURES, Direction.SHORT);
        TradingPlan plan = savedDraft(request, "100");
        when(market.currentMarkPrice("BTCUSDT")).thenReturn(Optional.of(new BigDecimal(mark)));
        currentPrice(MarketType.FUTURES, last);

        if (refusedField.isEmpty()) {
            assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
        } else {
            assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                    .satisfies(e -> assertFields(e, Map.of(refusedField, "MSG16")));
            assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        }
    }

    @Test
    void BR33_anUntriggeredFuturesShortStop_isActivatedAndWaits() {
        TradingPlan plan = savedDraft(stop(MarketType.FUTURES, Direction.SHORT));
        currentPrice(MarketType.FUTURES, "100.01");

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
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

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
        verify(market, Mockito.times(1)).currentLastPrice(MarketType.SPOT, "BTCUSDT");
    }

    /** The invalidation check and the immediate-fill decision of a LIMIT see the same read of the last price. */
    @ParameterizedTest(name = "last price {0} -> {1}")
    @CsvSource({"101, ACTIVE", "100, EXECUTED"})
    void BR33_BR68_activatingALimitWithAnInvalidationPrice_readsTheLastPriceOnce(String last, PlanStatus expected) {
        TradingPlan plan = savedDraft(withValidity(spotLimit("100", "95", "110"), "102", null, null), "101");
        currentPrice(MarketType.SPOT, last);
        Mockito.clearInvocations(market);

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(expected);
        verify(market, Mockito.times(1)).currentLastPrice(MarketType.SPOT, "BTCUSDT");
    }

    @Test
    void BR33_BR68_savingALimitWithAnInvalidationPriceAndActivating_readsTheLastPriceOnce() {
        currentPrice(MarketType.SPOT, "100");

        TradingPlanResponse plan =
                service.create(USER, activating(withValidity(spotLimit("100", "95", "110"), "102", null, null)));

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        verify(market, Mockito.times(1)).currentLastPrice(MarketType.SPOT, "BTCUSDT");
    }

    /** A MARKET entry fills at the price it was calculated with; the activation reads no second price. */
    @Test
    void BR33_activatingASpotMarketPlan_readsTheLastPriceOnce() {
        TradingPlan plan = savedDraft(spotMarket("95", "110"), "100");
        Mockito.clearInvocations(market);

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.EXECUTED);
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
                        create(PAIR, MarketType.FUTURES, Direction.LONG, entryType, "100", "95", "110", null, 2),
                        entryType == EntryType.LIMIT ? "102" : "98",
                        null,
                        null),
                entryType == EntryType.LIMIT ? "101" : "99");
        when(market.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).thenReturn(Optional.empty());
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "FREE has no Futures", "PRO"))
                .when(entitlements)
                .requireFeature(USER, Feature.FUTURES_ANALYSIS);

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
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
                create(PAIR, marketType, direction, entryType, entry, stop, takeProfit, null, null),
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

    /** The panel refuses what a save refuses: a MARKET plan carrying an invalidation price. */
    @Test
    void MSG01_thePanel_refusesAMarketPlanWithAnInvalidationPrice_asASaveDoes() {
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("100")));

        assertThatThrownBy(() -> service.calculate(USER, withValidity(spotMarket("95", "110"), "120", null, null)))
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
                create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, 2),
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

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
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

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
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

        service.activate(USER, plan.getId(), false);

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

    /** D-112: Save and Activate keeps the score of the preset's latest closed candle and the preset. */
    @Test
    void BR70_saveAndActivate_recordsTheSetupScoreAndItsPreset() {
        when(styles.styleOf(USER)).thenReturn(Optional.of(SetupStyle.DAY_TRADING));
        when(market.closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null, NOW))
                .thenReturn(new SetupScoreReading(62, SetupStyle.DAY_TRADING, "v1", "1h", NOW.minusSeconds(3600)));

        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.setupScoreAtPlan()).isEqualByComparingTo("62");
        assertThat(plan.setupScorePreset()).isEqualTo("DAY_TRADING v1");
    }

    @Test
    void BR70_activate_recordsTheSetupScore() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        assertThat(plan.getSetupScorePreset()).as("a draft has none").isNull();
        when(market.closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", null, null, NOW))
                .thenReturn(new SetupScoreReading(48, SetupStyle.DAY_TRADING, "v1", "1h", NOW.minusSeconds(3600)));
        when(styles.styleOf(USER)).thenReturn(Optional.empty());

        TradingPlanResponse active = service.activate(USER, plan.getId(), false);

        assertThat(active.setupScoreAtPlan()).isEqualByComparingTo("48");
        assertThat(active.setupScorePreset()).isEqualTo("DAY_TRADING v1");
    }

    /** A score that cannot be read is stored as null beside its preset; the activation goes on. */
    @Test
    void BR70_aNullSetupScore_neverBlocksTheActivation() {
        TradingPlanResponse plan = service.create(USER, activating(spotLimit("100", "95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.setupScoreAtPlan()).isNull();
        assertThat(plan.setupScorePreset()).isEqualTo("DAY_TRADING v1");
    }

    /** A failed read stores neither score nor preset, logs a warning naming the plan, and the plan still activates. */
    @Test
    void BR70_aFailedSetupScoreRead_storesNullsWarnsAndActivates() {
        TradingPlan plan = draft();
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(market.closedCandleSetupScore(any(), any(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("indicator store unavailable"));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(TradingPlanServiceImpl.class);
        logger.addAppender(logs);

        TradingPlanResponse active;
        try {
            active = service.activate(USER, plan.getId(), false);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(active.setupScoreAtPlan()).isNull();
        assertThat(active.setupScorePreset()).isNull();
        verify(events).publishEvent(any(TradingPlanActivated.class));
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(plan.getId().toString());
        });
    }

    @Test
    void BR70_aMarketPlanFilledAtActivation_keepsTheSetupScoreOfItsActivation() {
        currentPrice(MarketType.SPOT, "100");
        when(market.closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", null, null, NOW))
                .thenReturn(new SetupScoreReading(70, SetupStyle.DAY_TRADING, "v1", "1h", NOW.minusSeconds(3600)));
        when(styles.styleOf(USER)).thenReturn(Optional.empty());

        TradingPlanResponse plan = service.create(USER, activating(spotMarket("95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(plan.setupScoreAtPlan()).isEqualByComparingTo("70");
    }

    /**
     * D-112: "at plan" is the activation, so the preset is the one the Trader's style selects then, and the score is
     * read on that preset's timeframe (SWING 4h), while the analysis timeframe stays the one the draft saved (1h).
     */
    @Test
    void BR70_thePreset_isTheTradersAtActivation_notAtTheDraftSave() {
        when(styles.styleOf(USER)).thenReturn(Optional.of(SetupStyle.DAY_TRADING));
        TradingPlan plan = draft();
        assertThat(plan.getAnalysisTimeframe()).isEqualTo(AnalysisTimeframe.ONE_HOUR);
        when(plans.findByIdAndUserId(plan.getId(), USER)).thenReturn(Optional.of(plan));
        when(styles.styleOf(USER)).thenReturn(Optional.of(SetupStyle.SWING));

        TradingPlanResponse active = service.activate(USER, plan.getId(), false);

        verify(market).closedCandleSetupScore(PAIR, MarketType.SPOT, "4h", SetupStyle.SWING, null, NOW);
        assertThat(active.setupScorePreset()).isEqualTo("SWING v1");
        assertThat(active.analysisTimeframe()).isEqualTo("1h");
    }

    /**
     * Q-77: a plan analysed on another timeframe than its preset's stores the score of the preset's own timeframe, the
     * one the analysis screen shows; the analysis timeframe is kept for the chart and the fill.
     */
    @Test
    void BR70_aPlanOnAnotherTimeframeThanItsPresets_storesTheScoreOfThePresetsTimeframe() {
        when(styles.styleOf(USER)).thenReturn(Optional.of(SetupStyle.DAY_TRADING));
        when(market.closedCandleSetupScore(PAIR, MarketType.SPOT, "1h", SetupStyle.DAY_TRADING, null, NOW))
                .thenReturn(new SetupScoreReading(59, SetupStyle.DAY_TRADING, "v1", "1h", NOW.minusSeconds(3600)));

        TradingPlanResponse plan =
                service.create(USER, activating(withValidity(spotLimit("100", "95", "110"), null, null, "4h")));

        assertThat(plan.analysisTimeframe()).isEqualTo("4h");
        assertThat(plan.setupScoreAtPlan()).isEqualByComparingTo("59");
        assertThat(plan.setupScorePreset()).isEqualTo("DAY_TRADING v1");
        verify(market, never()).closedCandleSetupScore(any(), any(), eq("4h"), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------
    // Slippage and the fee-aware risk panel (TP-0: D-111 E5, D-112 R6 and its note, D-116)
    // ------------------------------------------------------------------------------------------

    /**
     * A LIMIT the last price already reaches fills at activation at the last price moved by SLIPPAGE_BPS (10), never
     * past its limit, and pays taker. LONG LIMIT 100 on Spot; SHORT LIMIT 100 on Futures.
     */
    @ParameterizedTest(name = "{0} LIMIT 100, last {1} -> fill {2}")
    @CsvSource({"LONG, 99.95, 100.00", "LONG, 99.00, 99.10", "SHORT, 100.05, 100.00", "SHORT, 101.00, 100.89"})
    void D112_aMarketableLimit_slipsAtActivation_butNeverPastItsLimit(Direction direction, String last, String fill) {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        CreateTradingPlanRequest request = direction == Direction.LONG
                ? spotLimit("100", "95", "110")
                : create(PAIR, MarketType.FUTURES, direction, EntryType.LIMIT, "100", "105", "90", null, 2);
        currentPrice(request.market(), last);

        assertThat(service.create(USER, activating(request)).status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(saved.getFillPrice()).isEqualByComparingTo(fill);
        assertThat(saved.getFillLiquidity()).isEqualTo(LiquidityRole.TAKER);
        assertThat(saved.getEntryPrice()).as("the plan keeps its limit").isEqualByComparingTo("100");
    }

    /**
     * D-112 R6: with 10 bps a LONG MARKET at last price 100 is calculated, sized and filled at 100.10, the price paid:
     * risk 10 over 5.10 is 1.960 units.
     */
    @Test
    void D112_aMarketPlan_isSizedAndFilledAtThePricePaid() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        currentPrice(MarketType.SPOT, "100");

        TradingPlanResponse plan = service.create(USER, activating(spotMarket("95", "110")));

        assertThat(plan.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(plan.entryPrice())
                .as("the reference price, as the panel answers it")
                .isEqualByComparingTo("100");
        assertThat(plan.expectedFillPrice()).isEqualByComparingTo("100.10");
        assertThat(plan.snapshot().positionQuantity()).isEqualByComparingTo("1.96");
        assertThat(saved.getEntryPrice()).isEqualByComparingTo("100.10");
        assertThat(saved.getPlannedEntryPrice()).isEqualByComparingTo("100");
        assertThat(saved.getFillPrice()).isEqualByComparingTo("100.10");
    }

    /**
     * D-112 R6: the panel answers a MARKET entry's reference price (the last price, 100.00) as {@code entryPrice} and
     * beside it the expected fill the quantity is sized on: the same price at 0 bps; with 10 bps a LONG buys at 100.10
     * and a SHORT sells at 99.90, against the Trader.
     */
    @ParameterizedTest(name = "{0} at {1} bps -> expected fill {2}")
    @CsvSource({"LONG, 0, 100.00", "LONG, 10, 100.10", "SHORT, 0, 100.00", "SHORT, 10, 99.90"})
    void D112_thePanel_answersTheExpectedFillOfAMarketEntry_besideItsReferencePrice(
            Direction direction, int bps, String expectedFill) {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(bps);
        currentPrice(MarketType.FUTURES, "100.00");
        CreateTradingPlanRequest request = direction == Direction.LONG
                ? create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, "95", "110", null, 5)
                : create(PAIR, MarketType.FUTURES, direction, EntryType.MARKET, null, "105", "90", null, 5);

        PlanCalculationResponse panel = service.calculate(USER, request);

        assertThat(panel.entryPrice()).isEqualByComparingTo("100.00");
        assertThat(panel.expectedFillPrice()).isEqualByComparingTo(expectedFill);
        if (bps == 0) {
            assertThat(panel.expectedFillPrice()).isEqualTo(panel.entryPrice());
        }
    }

    /** A LIMIT or STOP entry has no expected fill in the panel: it fills at its own price or later. */
    @Test
    void D112_aLimitEntry_hasNoExpectedFillInThePanel() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal("101")));

        PlanCalculationResponse panel = service.calculate(USER, spotLimit("100", "95", "110"));

        assertThat(panel.entryPrice()).isEqualByComparingTo("100");
        assertThat(panel.expectedFillPrice()).isNull();
    }

    /** SLIPPAGE_BPS is read for every fill, never kept from an earlier one: 0, then 10. */
    @Test
    void BR36_theSlippage_isReadForEveryFill() {
        currentPrice(MarketType.SPOT, "99.00");
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(0);
        service.create(USER, activating(spotLimit("100", "95", "110")));
        assertThat(saved.getFillPrice()).isEqualByComparingTo("99.00");

        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        service.create(USER, activating(spotLimit("100", "95", "110")));
        assertThat(saved.getFillPrice()).isEqualByComparingTo("99.10");
    }

    /**
     * D-116: the panel answers the fee figures of the Spot vector (LONG resting LIMIT 100 / 98 / 106, risk 100), at
     * the stored scale; a resting LIMIT pays maker. The quantity is still sized on the stop distance.
     */
    @Test
    void D116_thePanel_answersTheFeeFiguresAtTheStoredScale() {
        profileCapital("10000");
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        PlanSnapshotResponse snapshot = service.calculate(
                        USER,
                        create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, "100", "98", "106", "1", null))
                .snapshot();

        assertThat(snapshot.positionQuantity()).isEqualByComparingTo("50");
        assertThat(snapshot.riskAmount()).isEqualByComparingTo("100");
        assertThat(snapshot.estimatedEntryFee()).isEqualTo(new BigDecimal("5.00000000"));
        assertThat(snapshot.estimatedStopLossExitFee()).isEqualTo(new BigDecimal("4.90000000"));
        assertThat(snapshot.estimatedTakeProfitExitFee()).isEqualTo(new BigDecimal("5.30000000"));
        assertThat(snapshot.estimatedSlippage()).isEqualTo(new BigDecimal("0E-8"));
        assertThat(snapshot.lossAtStopLoss()).isEqualTo(new BigDecimal("109.90000000"));
        assertThat(snapshot.feeShareOfRisk()).isEqualTo(new BigDecimal("0.09900000"));
        assertThat(snapshot.netRiskReward()).isEqualTo(new BigDecimal("2.63603276"));
    }

    /**
     * D-116: a Futures MARKET whose fees and slippage are 20 % of its risk raises HIGH_FEE_SHARE, read from
     * FEE_RISK_WARN_RATIO on every calculation, and never blocks activation.
     */
    @Test
    void D116_aHighFeeShare_warnsFromTheSettingOfEachCalculation_andNeverBlocks() {
        profileCapital("10000");
        currentPrice(MarketType.FUTURES, "100250");
        CreateTradingPlanRequest request =
                create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.MARKET, null, "99750", "101750", "1", 5);

        PlanCalculationResponse panel = service.calculate(USER, request);
        assertThat(panel.warnings()).extracting(PlanWarningResponse::type).contains(WarningType.HIGH_FEE_SHARE);
        assertThat(panel.blocksActivation()).isFalse();

        when(settings.decimal(PlanCalculationAssembler.FEE_RISK_WARN_RATIO)).thenReturn(new BigDecimal("0.21"));
        assertThat(service.calculate(USER, request).warnings())
                .extracting(PlanWarningResponse::type)
                .doesNotContain(WarningType.HIGH_FEE_SHARE);
        assertThat(service.create(USER, activating(request)).status()).isEqualTo(PlanStatus.EXECUTED);
    }

    // ------------------------------------------------------------------------------------------
    // BR-69: entry deviation of a MARKET activation (TP-C). The BTC example of TECHNICAL_DESIGN 7.8: LONG on Spot
    // planned 100,000, SL 98,000, TP 106,000; SHORT on Futures planned 100,000, SL 102,000, TP 94,000; risk 100 USDT
    // (capital 10,000 at 1 %), step 0.0001; 0 bps unless a test sets it.
    // ------------------------------------------------------------------------------------------

    /** Fill 100,000: 0R, quantity 0.05, RR 3.0, activated and filled without a confirmation. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LONG", "SHORT"})
    void BR69_aMarketEntryAtThePlannedPrice_isActivatedWithoutConfirmation(Direction direction) {
        TradingPlan plan = btcDraft(direction, null);

        TradingPlanResponse active = service.activate(USER, plan.getId(), false);

        assertThat(active.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(active.snapshot().positionQuantity()).isEqualByComparingTo("0.05");
        assertThat(active.snapshot().riskRewardRatio()).isEqualByComparingTo("3");
        assertThat(plan.getFillPrice()).isEqualByComparingTo("100000");
    }

    /**
     * Fill 101,000 LONG (99,000 SHORT): 0.5R, MSG51 with the quantity 0.0333 floored to the step, RR 1.67 and the
     * net-of-fee RR, the plan unchanged; repeated with the flag it is filled there.
     */
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"LONG, 101000, 1.49828071", "SHORT, 99000, 1.59061442"})
    void BR69_halfAnRFromThePlan_answersMsg51WithTheRecalculatedPlan_untilConfirmed(
            Direction direction, String fill, String netRiskReward) {
        TradingPlan plan = btcDraft(direction, null);
        currentPrice(market(direction), fill);

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_CONFIRMATION_REQUIRED);
                    assertThat(e.messageArgs()).containsExactly("0.50000000", "0.033300000000", "1.66666667");
                    assertThat(e.problemProperties())
                            .containsExactly(
                                    Map.entry(EntryDeviationException.DEVIATION_R, "0.50000000"),
                                    Map.entry(EntryDeviationException.MAX_DEVIATION_R, "0.75"),
                                    Map.entry(EntryDeviationException.EXPECTED_FILL_PRICE, fill),
                                    Map.entry(EntryDeviationException.POSITION_QUANTITY, "0.033300000000"),
                                    Map.entry(EntryDeviationException.RISK_REWARD_RATIO, "1.66666667"),
                                    Map.entry(EntryDeviationException.NET_RISK_REWARD, netRiskReward));
                });
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100000");
        assertThat(plan.getSnapshot().positionQuantity()).isEqualByComparingTo("0.05");
        verify(plans).save(plan);

        TradingPlanResponse active = service.activate(USER, plan.getId(), true);

        assertThat(active.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(active.snapshot().positionQuantity()).isEqualByComparingTo("0.0333");
        assertThat(plan.getFillPrice()).isEqualByComparingTo(fill);
        assertThat(plan.getPlannedEntryPrice()).isEqualByComparingTo("100000");
    }

    /**
     * D-121: the MSG51 recalculation is sized on the profile capital of the activation, read once for the request, not
     * on the capital of the draft's save: 20,000 at 1 % over the 3,000 stop distance of a 101,000 fill is 0.0666.
     */
    @Test
    void BR69_theMsg51Recalculation_isSizedOnTheProfileCapitalReadOnce() {
        TradingPlan plan = btcDraft(Direction.LONG, null);
        profileCapital("20000");
        clearInvocations(riskProfiles);
        currentPrice(MarketType.SPOT, "101000");

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> assertThat(e.problemProperties())
                        .containsEntry(EntryDeviationException.POSITION_QUANTITY, "0.066600000000"));
        verify(riskProfiles, times(1)).planDefaultsOf(USER);
        assertThat(plan.getCapital()).isEqualByComparingTo("10000");
    }

    /**
     * BR-69, BR-34: the MSG51 refusal opens no position; the same activation confirmed is written at once and opens its
     * position at the moved fill (101,000 LONG, 99,000 SHORT) with the recalculated quantity 0.0333.
     */
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"LONG, 101000", "SHORT, 99000"})
    void BR69_aConfirmedMovedMarketEntry_opensItsPositionInTheJournal(Direction direction, String fill) {
        TradingPlan plan = btcDraft(direction, null);
        currentPrice(market(direction), fill);
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOf(EntryDeviationException.class);
        verify(journal, never()).openSimulatedTrade(any());

        service.activate(USER, plan.getId(), true);

        ArgumentCaptor<SimulatedTradeOpening> opening = ArgumentCaptor.forClass(SimulatedTradeOpening.class);
        InOrder order = inOrder(plans, journal);
        order.verify(plans).saveAndFlush(plan);
        order.verify(journal).openSimulatedTrade(opening.capture());
        SimulatedTradeOpening opened = opening.getValue();
        assertThat(opened.planId()).isEqualTo(plan.getId());
        assertThat(opened.direction()).isEqualTo(direction);
        assertThat(opened.market()).isEqualTo(market(direction));
        assertThat(opened.entryPrice()).isEqualByComparingTo(fill);
        assertThat(opened.quantity()).isEqualByComparingTo("0.0333");
        assertThat(opened.entryLiquidity()).isEqualTo(LiquidityRole.TAKER);
        assertThat(opened.entryTime()).isEqualTo(NOW);
    }

    /**
     * Fill 102,000 LONG (98,000 SHORT): 1.0R, above 0.75, MSG52 even with the flag; the panel at that price shows the
     * quantity 0.025 and RR 1.0 the plan would have had.
     */
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"LONG, 102000", "SHORT, 98000"})
    void BR69_oneRFromThePlan_isRefusedWithMsg52_evenWhenConfirmed(Direction direction, String fill) {
        TradingPlan plan = btcDraft(direction, null);
        currentPrice(market(direction), fill);

        for (boolean confirmed : new boolean[] {false, true}) {
            assertThatThrownBy(() -> service.activate(USER, plan.getId(), confirmed))
                    .isInstanceOfSatisfying(EntryDeviationException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_REFUSED);
                        assertThat(e.messageArgs()).containsExactly("1.00000000", "0.75");
                        assertThat(e.problemProperties())
                                .containsEntry(EntryDeviationException.TAKE_PROFIT_REACHED, false)
                                .doesNotContainKey(EntryDeviationException.POSITION_QUANTITY);
                    });
        }
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);

        PlanSnapshotResponse wouldBe =
                service.calculate(USER, btc(direction, null)).snapshot();
        assertThat(wouldBe.positionQuantity()).isEqualByComparingTo("0.025");
        assertThat(wouldBe.riskRewardRatio()).isEqualByComparingTo("1");
    }

    /**
     * The bands' edges, LONG and SHORT: exactly 0.25 and exactly 0.75 need confirmation, one cent past 0.75 is refused,
     * one cent short of 0.25 and a better price (negative R) are filled.
     */
    @ParameterizedTest(name = "{0} at {1} -> {2}")
    @CsvSource({
        "LONG,  100499.99, EXECUTED",
        "LONG,  100500,    MSG51",
        "LONG,  101500,    MSG51",
        "LONG,  101500.01, MSG52",
        "LONG,  99000,     EXECUTED",
        "SHORT, 99500.01,  EXECUTED",
        "SHORT, 99500,     MSG51",
        "SHORT, 98500,     MSG51",
        "SHORT, 98499.99,  MSG52",
        "SHORT, 101000,    EXECUTED"
    })
    void BR69_theBoundaries_ofTheConfirmationBand(Direction direction, String fill, String expected) {
        TradingPlan plan = btcDraft(direction, null);
        currentPrice(market(direction), fill);

        switch (expected) {
            case "EXECUTED" ->
                assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.EXECUTED);
            case "MSG51" ->
                assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.ENTRY_DEVIATION_CONFIRMATION_REQUIRED));
            default ->
                assertThatThrownBy(() -> service.activate(USER, plan.getId(), true))
                        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.ENTRY_DEVIATION_REFUSED));
        }
    }

    /** A fill at or past the take profit is MSG52, not the sizing's MSG16 on the take profit, even with the flag. */
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"LONG, 106000", "LONG, 107000", "SHORT, 94000", "SHORT, 93000"})
    void BR69_aFillAtOrPastTheTakeProfit_isRefusedWithMsg52(Direction direction, String fill) {
        TradingPlan plan = btcDraft(direction, new BigDecimal("0.75"));
        currentPrice(market(direction), fill);

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), true))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_REFUSED);
                    assertThat(e.problemProperties()).containsEntry(EntryDeviationException.TAKE_PROFIT_REACHED, true);
                });
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    /** A plan's own limit of 0.5 refuses 0.6R, which the system's 0.75 would only have asked to confirm. */
    @Test
    void BR69_aPlanLimitOfHalfAnR_refuses0point6R() {
        TradingPlan plan = btcDraft(Direction.LONG, new BigDecimal("0.5"));
        currentPrice(MarketType.SPOT, "101200");

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), true))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_REFUSED);
                    assertThat(e.messageArgs()).containsExactly("0.60000000", "0.5");
                });
    }

    /** A plan's own limit above ENTRY_DEVIATION_BLOCK_R (0.75) is refused at save, MSG15 on its field. */
    @Test
    void BR69_aPlanLimitAboveTheBlockValue_isRefusedAtSave() {
        currentPrice(MarketType.SPOT, "100000");

        assertThatThrownBy(() -> service.create(USER, btc(Direction.LONG, new BigDecimal("0.76"))))
                .satisfies(e -> assertFields(e, Map.of("maxEntryDeviationR", "MSG15")));
        verify(plans, never()).save(any());
    }

    /**
     * The deviation is measured to the expected fill, slippage included: last 100,400 is 0.2R and is filled at 0 bps;
     * with 10 bps the fill is 100,500.40, 0.2502R, which needs confirmation. The planned entry stays the unslipped
     * reference of the save, 100,000, even though the draft was saved with 10 bps.
     */
    @Test
    void BR69_theSlippage_isPartOfTheDeviation() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        TradingPlan plan = btcDraft(Direction.LONG, null);
        assertThat(plan.getPlannedEntryPrice()).isEqualByComparingTo("100000");
        currentPrice(MarketType.SPOT, "100400");

        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> assertThat(e.problemProperties())
                        .containsEntry(EntryDeviationException.DEVIATION_R, "0.25020000")
                        .containsEntry(EntryDeviationException.EXPECTED_FILL_PRICE, "100500.40"));

        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(0);
        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(plan.getFillPrice()).isEqualByComparingTo("100400");
    }

    /** The thresholds are read from the settings on every activation, not kept: 0.25 asks, 0.6 lets 0.5R through. */
    @Test
    void BR69_theThresholds_areReadOnEveryActivation() {
        TradingPlan plan = btcDraft(Direction.LONG, null);
        currentPrice(MarketType.SPOT, "101000");
        assertThatThrownBy(() -> service.activate(USER, plan.getId(), false))
                .isInstanceOf(EntryDeviationException.class);

        when(settings.decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_CONFIRM_R)).thenReturn(new BigDecimal("0.6"));

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.EXECUTED);
    }

    /**
     * Save and Activate measures from the reference of its own save, so only the slippage moves it: SL 100 below a
     * last price of 100,000 is 1R = 100, and 3 bps buys at 100,030, 0.3R: MSG51 and nothing saved, then filled with
     * the flag. Capital 100,000 at 0.1 % keeps the risk at 100.
     */
    @Test
    void BR69_saveAndActivate_asksForConfirmation_andActivatesWithTheFlag() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(3);
        profileCapital("100000");
        currentPrice(MarketType.SPOT, "100000");
        CreateTradingPlanRequest request = activating(
                create(BTC, MarketType.SPOT, Direction.LONG, EntryType.MARKET, null, "99900", "100500", "0.1", null));

        assertThatThrownBy(() -> service.create(USER, request))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> assertThat(e.problemProperties())
                        .containsEntry(EntryDeviationException.DEVIATION_R, "0.30000000")
                        .containsEntry(EntryDeviationException.EXPECTED_FILL_PRICE, "100030.00"));
        verify(plans, never()).save(any());

        TradingPlanResponse active = service.create(USER, confirmed(request));

        assertThat(active.status()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(saved.getPlannedEntryPrice()).isEqualByComparingTo("100000");
        assertThat(saved.getFillPrice()).isEqualByComparingTo("100030.00");
    }

    /** Edit and activate: the same check and the same flag, from the update's own save. */
    @Test
    void BR69_editAndActivate_asksForConfirmation_andActivatesWithTheFlag() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(3);
        profileCapital("100000");
        TradingPlan plan = savedDraft(
                create(BTC, MarketType.SPOT, Direction.LONG, EntryType.MARKET, null, "99900", "100500", "0.1", null),
                "100000");

        assertThatThrownBy(() -> service.update(USER, plan.getId(), btcEdit(false)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.ENTRY_DEVIATION_CONFIRMATION_REQUIRED));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);

        assertThat(service.update(USER, plan.getId(), btcEdit(true)).status()).isEqualTo(PlanStatus.EXECUTED);
    }

    /** Save and Activate whose expected fill reaches the take profit is MSG52, not MSG16 on the take profit. */
    @Test
    void BR69_saveAndActivate_pastTheTakeProfit_isMsg52() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        profileCapital("10000");
        currentPrice(MarketType.SPOT, "100000");

        assertThatThrownBy(() -> service.create(
                        USER,
                        activating(create(
                                BTC,
                                MarketType.SPOT,
                                Direction.LONG,
                                EntryType.MARKET,
                                null,
                                "98000",
                                "100050",
                                "1",
                                null))))
                .isInstanceOfSatisfying(EntryDeviationException.class, e -> assertThat(e.problemProperties())
                        .containsEntry(EntryDeviationException.TAKE_PROFIT_REACHED, true));
    }

    /** A BLOCKING warning answers MSG18 before any confirmation is asked for: confirming would not activate it. */
    @Test
    void BR69_aBlockingWarning_answersMsg18_beforeMsg51() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(3);
        profileCapital("10000");
        currentPrice(MarketType.SPOT, "100000");
        CreateTradingPlanRequest request = activating(
                create(BTC, MarketType.SPOT, Direction.LONG, EntryType.MARKET, null, "99900", "100500", "1", null));

        assertThatThrownBy(() -> service.create(USER, request)).isInstanceOf(PlanActivationBlockedException.class);
    }

    /** Only MARKET is checked: a LIMIT or STOP reads no deviation threshold at activation. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LIMIT", "STOP"})
    void BR69_limitAndStopEntries_haveNoActivationDeviation(EntryType entryType) {
        TradingPlan plan = savedDraft(
                create(PAIR, MarketType.SPOT, Direction.LONG, entryType, "100", "95", "110", null, null),
                entryType == EntryType.LIMIT ? "100.4" : "99");

        assertThat(service.activate(USER, plan.getId(), false).status()).isEqualTo(PlanStatus.ACTIVE);
        verify(settings, never()).decimal(TradingPlanServiceImpl.ENTRY_DEVIATION_CONFIRM_R);
    }

    /** The BTC draft of the BR-69 tests, saved while the last price is 100,000. */
    private TradingPlan btcDraft(Direction direction, BigDecimal maxDeviation) {
        return savedDraft(btc(direction, maxDeviation), "100000");
    }

    /** The BTC request of the BR-69 tests, on a profile capital of 10,000 (D-121). */
    private CreateTradingPlanRequest btc(Direction direction, BigDecimal maxDeviation) {
        profileCapital("10000");
        CreateTradingPlanRequest request = direction == Direction.LONG
                ? create(BTC, MarketType.SPOT, direction, EntryType.MARKET, null, "98000", "106000", "1", null)
                : create(BTC, MarketType.FUTURES, direction, EntryType.MARKET, null, "102000", "94000", "1", 2);
        return withValidity(request, null, maxDeviation == null ? null : maxDeviation.toPlainString(), null);
    }

    private static MarketType market(Direction direction) {
        return direction == Direction.LONG ? MarketType.SPOT : MarketType.FUTURES;
    }

    private static UpdateTradingPlanRequest btcEdit(boolean confirmDeviation) {
        return new UpdateTradingPlanRequest(
                MarketType.SPOT,
                Direction.LONG,
                EntryType.MARKET,
                null,
                decimal("99900"),
                decimal("100500"),
                decimal("0.1"),
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                confirmDeviation);
    }

    private static CreateTradingPlanRequest confirmed(CreateTradingPlanRequest r) {
        return new CreateTradingPlanRequest(
                r.pairId(),
                r.market(),
                r.direction(),
                r.entryType(),
                r.entryPrice(),
                r.stopLoss(),
                r.takeProfit(),
                r.riskPercent(),
                r.leverage(),
                r.expiresAt(),
                r.note(),
                r.invalidationPrice(),
                r.maxEntryDeviationR(),
                r.analysisTimeframe(),
                r.activate(),
                true);
    }

    private void currentPrice(MarketType marketType, String price) {
        when(market.currentLastPrice(marketType, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(price)));
    }

    /** A STOP at 100: LONG on Spot (SL 95, TP 110), SHORT on Futures (SL 105, TP 90, leverage 2). */
    private static CreateTradingPlanRequest stop(MarketType marketType, Direction direction) {
        return direction == Direction.LONG
                ? create(PAIR, marketType, direction, EntryType.STOP, "100", "95", "110", null, null)
                : create(PAIR, marketType, direction, EntryType.STOP, "100", "105", "90", null, 2);
    }

    /** A LIMIT at 100: LONG on Spot (SL 95, TP 110), SHORT on Futures (SL 105, TP 90, leverage 2). */
    private static CreateTradingPlanRequest limit(MarketType marketType, Direction direction) {
        return direction == Direction.LONG
                ? create(PAIR, marketType, direction, EntryType.LIMIT, "100", "95", "110", null, null)
                : create(PAIR, marketType, direction, EntryType.LIMIT, "100", "105", "90", null, 2);
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
                decimal(invalidation),
                null,
                null,
                false,
                null);
    }

    private static CreateTradingPlanRequest spotStop(String entry, String stop, String takeProfit) {
        return create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.STOP, entry, stop, takeProfit, null, null);
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
                r.riskPercent(),
                r.leverage(),
                expiresAt,
                r.note(),
                r.invalidationPrice(),
                r.maxEntryDeviationR(),
                r.analysisTimeframe(),
                r.activate(),
                r.confirmDeviation());
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
                r.riskPercent(),
                r.leverage(),
                r.expiresAt(),
                r.note(),
                decimal(invalidation),
                decimal(maxDeviation),
                timeframe,
                r.activate(),
                r.confirmDeviation());
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
                false,
                null);
    }

    /** The default capital of the Trader's profile from now on (D-121); {@code null} for none. */
    private void profileCapital(String capital) {
        when(riskProfiles.planDefaultsOf(USER)).thenReturn(new PlanDefaults(BALANCED, decimal(capital), null));
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
        return create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.LIMIT, entry, stop, takeProfit, null, null);
    }

    private static CreateTradingPlanRequest spotMarket(String stop, String takeProfit) {
        return create(PAIR, MarketType.SPOT, Direction.LONG, EntryType.MARKET, null, stop, takeProfit, null, null);
    }

    private static CreateTradingPlanRequest futuresMarket() {
        return create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.MARKET, null, "95", "110", null, 5);
    }

    private static CreateTradingPlanRequest futuresLimit(int leverage) {
        return create(PAIR, MarketType.FUTURES, Direction.LONG, EntryType.LIMIT, "100", "95", "110", null, leverage);
    }

    private static CreateTradingPlanRequest create(
            UUID pair,
            MarketType market,
            Direction direction,
            EntryType entryType,
            String entry,
            String stop,
            String takeProfit,
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
                decimal(riskPercent),
                leverage,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
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
                r.riskPercent(),
                r.leverage(),
                r.expiresAt(),
                r.note(),
                r.invalidationPrice(),
                r.maxEntryDeviationR(),
                r.analysisTimeframe(),
                true,
                r.confirmDeviation());
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
                activate,
                null);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** An OPEN LONG position of the Trader at {@code entry} with its current stop loss and quantity. */
    private static OpenTrade openTrade(String entry, String stopLoss, String quantity) {
        return openTrade(Direction.LONG, entry, stopLoss, quantity);
    }

    private static OpenTrade openTrade(Direction direction, String entry, String stopLoss, String quantity) {
        return new OpenTrade(
                UUID.randomUUID(),
                UUID.randomUUID(),
                USER,
                PAIR,
                direction == Direction.LONG ? MarketType.SPOT : MarketType.FUTURES,
                direction,
                new BigDecimal(entry),
                NOW,
                new BigDecimal(quantity),
                LiquidityRole.TAKER,
                BigDecimal.ZERO,
                new BigDecimal(stopLoss),
                null,
                null,
                0L);
    }
}

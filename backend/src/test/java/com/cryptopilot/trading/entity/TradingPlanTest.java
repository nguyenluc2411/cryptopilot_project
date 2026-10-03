package com.cryptopilot.trading.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.exception.PlanActivationBlockedException;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** The plan aggregate: its lifecycle (BR-32), its snapshot (BR-31) and the input rules it delegates (BR-21, BR-22). */
class TradingPlanTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-00000000a001");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofHours(2));
    private static final Instant EXPIRY = NOW.plus(Duration.ofDays(7));
    private static final PlanDetails LIMIT = new PlanDetails(EntryType.LIMIT, EXPIRY, "breakout retest");
    private static final PlanDetails MARKET = new PlanDetails(EntryType.MARKET, null, null);

    private static final PlanWarning BLOCKING =
            new PlanWarning(WarningType.INSUFFICIENT_CAPITAL, "margin above capital");
    private static final PlanWarning WARNING = new PlanWarning(WarningType.LOW_RR, "risk/reward 1.2");
    private static final PlanWarning INFO = new PlanWarning(WarningType.WIDE_STOP_LOSS, "stop 12 % away");

    // ------------------------------------------------------------------------------------------
    // Saving a draft
    // ------------------------------------------------------------------------------------------

    @Test
    void aNewPlan_isADraftWithTheCalculatedValuesItsSnapshotAndItsWarnings() {
        PlanCalculation calculation = PlanFixture.futuresLong().build();

        TradingPlan plan = TradingPlan.draft(USER, PAIR, calculation, LIMIT, List.of(WARNING, INFO));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.isEditable()).isTrue();
        assertThat(plan.getUserId()).isEqualTo(USER);
        assertThat(plan.getPairId()).isEqualTo(PAIR);
        assertThat(plan.getMarket()).isEqualTo(MarketType.FUTURES);
        assertThat(plan.getDirection()).isEqualTo(Direction.LONG);
        assertThat(plan.getEntryType()).isEqualTo(EntryType.LIMIT);
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100");
        assertThat(plan.getStopLoss()).isEqualByComparingTo("95");
        assertThat(plan.getTakeProfit()).isEqualByComparingTo("110");
        assertThat(plan.getCapital()).isEqualByComparingTo("1000");
        assertThat(plan.getRiskPercent()).isEqualByComparingTo("1");
        assertThat(plan.getLeverage()).isEqualTo(5);
        assertThat(plan.getMarginMode()).isEqualTo(MarginMode.ISOLATED);
        assertThat(plan.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(plan.getNote()).isEqualTo("breakout retest");
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(calculation));
        assertThat(plan.getWarnings()).containsExactly(WARNING, INFO);
        assertThat(plan.getActivatedAt()).isNull();
    }

    @Test
    void BR21_aSpotPlan_storesNoLeverageNoMarginModeAndNoLiquidationFields() {
        TradingPlan plan = TradingPlan.draft(USER, PAIR, PlanFixture.spot().build(), LIMIT, List.of());

        assertThat(plan.getMarket()).isEqualTo(MarketType.SPOT);
        assertThat(plan.getLeverage()).isNull();
        assertThat(plan.getMarginMode()).isNull();
        assertThat(plan.getSnapshot().initialMargin()).isNull();
        assertThat(plan.getSnapshot().maintenanceMarginRateUsed()).isNull();
        assertThat(plan.getSnapshot().estimatedLiquidationPrice()).isNull();
    }

    @Test
    void BR31_aSavedDraft_replacesItsValuesSnapshotAndWarnings_neverAppendsWarnings() {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of(WARNING));
        PlanCalculation edited =
                PlanFixture.futuresLong().stop("96").takeProfit("120").build();
        PlanDetails details = new PlanDetails(EntryType.MARKET, null, null);

        plan.updateDraft(edited, details, List.of(BLOCKING, INFO));

        assertThat(plan.getStopLoss()).isEqualByComparingTo("96");
        assertThat(plan.getTakeProfit()).isEqualByComparingTo("120");
        assertThat(plan.getEntryType()).isEqualTo(EntryType.MARKET);
        assertThat(plan.getExpiresAt()).isNull();
        assertThat(plan.getNote()).isNull();
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(edited));
        assertThat(plan.getWarnings()).containsExactly(BLOCKING, INFO);

        plan.updateDraft(edited, details, List.of());

        assertThat(plan.getWarnings()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PlanStatus.class,
            names = {"ACTIVE", "EXECUTED", "CANCELLED", "EXPIRED"})
    void BR32_aPlanOutsideDraft_cannotBeEdited(PlanStatus status) {
        TradingPlan plan = planIn(status);
        PlanSnapshot before = plan.getSnapshot();

        assertThat(plan.isEditable()).isFalse();
        assertThatThrownBy(() ->
                        plan.updateDraft(PlanFixture.futuresLong().stop("90").build(), LIMIT, List.of()))
                .isInstanceOf(IllegalPlanStateException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID))
                .satisfies(e ->
                        assertThat(((IllegalPlanStateException) e).status()).isEqualTo(status));
        assertThat(plan.getStopLoss()).isEqualByComparingTo("95");
        assertThat(plan.getSnapshot()).isEqualTo(before);
    }

    @Test
    void aWarningMessageLongerThanItsColumn_isRefused() {
        PlanWarning tooLong = new PlanWarning(WarningType.LOW_RR, "x".repeat(TradingPlanWarning.MESSAGE_LENGTH + 1));

        assertThatThrownBy(() ->
                        TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of(tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // BR-21 and BR-22, delegated to the sizing
    // ------------------------------------------------------------------------------------------

    static Stream<Arguments> rejectedInputs() {
        PlanCalculation spot = PlanFixture.spot().build();
        PlanCalculation futuresLong = PlanFixture.futuresLong().build();
        PlanCalculation futuresShort = PlanFixture.futuresShort().build();
        return Stream.of(
                rejected("BR-21 Spot SHORT", with(spot, Direction.SHORT, "100", "105", "90", 1), "direction", "MSG01"),
                rejected(
                        "BR-21 Spot leverage 2",
                        with(spot, Direction.LONG, "100", "95", "110", 2),
                        "leverage",
                        "MSG01"),
                rejected(
                        "BR-22 LONG stop above entry",
                        with(futuresLong, Direction.LONG, "100", "101", "110", 5),
                        "stopLoss",
                        "MSG16"),
                rejected(
                        "BR-22 LONG stop equal entry",
                        with(futuresLong, Direction.LONG, "100", "100", "110", 5),
                        "stopLoss",
                        "MSG16"),
                rejected(
                        "BR-22 LONG target below entry",
                        with(futuresLong, Direction.LONG, "100", "95", "99", 5),
                        "takeProfit",
                        "MSG16"),
                rejected(
                        "BR-22 LONG target equal entry",
                        with(futuresLong, Direction.LONG, "100", "95", "100", 5),
                        "takeProfit",
                        "MSG16"),
                rejected(
                        "BR-22 SHORT stop below entry",
                        with(futuresShort, Direction.SHORT, "100", "99", "90", 5),
                        "stopLoss",
                        "MSG16"),
                rejected(
                        "BR-22 SHORT stop equal entry",
                        with(futuresShort, Direction.SHORT, "100", "100", "90", 5),
                        "stopLoss",
                        "MSG16"),
                rejected(
                        "BR-22 SHORT target above entry",
                        with(futuresShort, Direction.SHORT, "100", "105", "101", 5),
                        "takeProfit",
                        "MSG16"),
                rejected(
                        "BR-22 SHORT target equal entry",
                        with(futuresShort, Direction.SHORT, "100", "105", "100", 5),
                        "takeProfit",
                        "MSG16"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedInputs")
    void BR21_BR22_inputsTheSizingRejects_areRefusedWhenADraftIsCreated(
            String rule, PlanCalculation calculation, Map<String, String> errors) {
        assertThatThrownBy(() -> TradingPlan.draft(USER, PAIR, calculation, LIMIT, List.of()))
                .satisfies(e -> assertValidationFailed(e, errors));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedInputs")
    void BR21_BR22_inputsTheSizingRejects_areRefusedWhenADraftIsEdited(
            String rule, PlanCalculation calculation, Map<String, String> errors) {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of(WARNING));

        assertThatThrownBy(() -> plan.updateDraft(calculation, LIMIT, List.of()))
                .satisfies(e -> assertValidationFailed(e, errors));
        assertThat(plan.getWarnings()).containsExactly(WARNING);
    }

    @Test
    void BR22_theDirectionsValidPriceOrders_areAccepted() {
        assertThat(TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of())
                        .getDirection())
                .isEqualTo(Direction.LONG);
        assertThat(TradingPlan.draft(USER, PAIR, PlanFixture.futuresShort().build(), LIMIT, List.of())
                        .getDirection())
                .isEqualTo(Direction.SHORT);
    }

    @Test
    void BR22_anActivationWithRejectedInputs_isRefused() {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of());
        PlanCalculation rejected = with(PlanFixture.futuresLong().build(), Direction.LONG, "100", "100", "110", 5);

        assertThatThrownBy(() -> plan.activate(rejected, List.of(), NOW))
                .satisfies(e -> assertValidationFailed(e, Map.of("stopLoss", "MSG16")));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    /**
     * A MARKET plan is recalculated at the last price when it is activated; if that price has already passed the stop
     * loss, the Trader gets the field error, not a server error, and the draft is left as it was.
     */
    static Stream<Arguments> marketPricePastTheStop() {
        return Stream.of(
                Arguments.of(
                        "LONG, last price below the stop",
                        PlanFixture.futuresLong(),
                        Direction.LONG,
                        "94",
                        "95",
                        "110"),
                Arguments.of(
                        "SHORT, last price above the stop",
                        PlanFixture.futuresShort(),
                        Direction.SHORT,
                        "106",
                        "105",
                        "90"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("marketPricePastTheStop")
    void BR22_aMarketPlanWhosePriceHasPassedTheStop_isRefusedAtActivationAndLeftUnchanged(
            String side, PlanFixture saved, Direction direction, String lastPrice, String stop, String takeProfit) {
        PlanCalculation draft = saved.build();
        TradingPlan plan = TradingPlan.draft(USER, PAIR, draft, MARKET, List.of(WARNING));
        PlanCalculation atLastPrice = with(draft, direction, lastPrice, stop, takeProfit, 5);

        assertThatThrownBy(() -> plan.activate(atLastPrice, List.of(), NOW))
                .satisfies(e -> assertValidationFailed(e, Map.of("stopLoss", "MSG16")));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getActivatedAt()).isNull();
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100");
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(draft));
        assertThat(plan.getWarnings()).containsExactly(WARNING);
        assertThat(plan.getVersion()).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Activation (BR-31, BR-32, MSG18)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR31_activation_storesTheNewSnapshotAndWarningsAndRecordsTheInstant() {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), MARKET, List.of(BLOCKING));
        PlanCalculation again = PlanFixture.futuresLong().entry("101").build();

        plan.activate(again, List.of(WARNING, INFO), NOW);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.getActivatedAt()).isEqualTo(NOW);
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(again));
        assertThat(plan.getWarnings()).containsExactly(WARNING, INFO);
        assertThat(plan.hasBlockingWarning()).isFalse();
    }

    @Test
    void MSG18_aBlockingWarning_refusesActivationAndLeavesTheDraftUnchanged() {
        PlanCalculation saved = PlanFixture.futuresLong().build();
        TradingPlan plan = TradingPlan.draft(USER, PAIR, saved, MARKET, List.of(WARNING));
        PlanCalculation again = PlanFixture.futuresLong().entry("101").build();
        assertThat(PlanSnapshot.of(again)).isNotEqualTo(PlanSnapshot.of(saved));

        assertThatThrownBy(() -> plan.activate(again, List.of(BLOCKING, WARNING), NOW))
                .isInstanceOf(PlanActivationBlockedException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.TRADING_BLOCKING_WARNING));

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.getActivatedAt()).isNull();
        assertThat(plan.getEntryPrice()).isEqualByComparingTo("100");
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(saved));
        assertThat(plan.getWarnings()).containsExactly(WARNING);
    }

    @Test
    void MSG18_aDraftSavedWithABlockingWarning_reportsIt() {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of(BLOCKING));

        assertThat(plan.hasBlockingWarning()).isTrue();
    }

    /** The decision is {@link WarningEvaluator#blocksActivation}'s: the plan follows its answer, whatever the list. */
    @Test
    void MSG18_whetherActivationIsBlocked_isDecidedByTheWarningEvaluator() {
        try (MockedStatic<WarningEvaluator> evaluator = Mockito.mockStatic(WarningEvaluator.class)) {
            evaluator.when(() -> WarningEvaluator.blocksActivation(anyList())).thenReturn(true);
            TradingPlan refused =
                    TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of());
            assertThatThrownBy(() -> refused.activate(PlanFixture.futuresLong().build(), List.of(INFO), NOW))
                    .isInstanceOf(PlanActivationBlockedException.class);
            assertThat(refused.hasBlockingWarning()).isTrue();

            evaluator.when(() -> WarningEvaluator.blocksActivation(anyList())).thenReturn(false);
            TradingPlan accepted =
                    TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of());
            accepted.activate(PlanFixture.futuresLong().build(), List.of(BLOCKING), NOW);
            assertThat(accepted.getStatus()).isEqualTo(PlanStatus.ACTIVE);

            evaluator.verify(() -> WarningEvaluator.blocksActivation(List.of(INFO)));
            evaluator.verify(() -> WarningEvaluator.blocksActivation(List.of(BLOCKING)));
        }
    }

    @ParameterizedTest
    @CsvSource({"-1", "0"})
    void BR32_anExpiryNotAfterTheActivation_isRefused(long secondsAfterNow) {
        PlanDetails details = new PlanDetails(EntryType.LIMIT, NOW.plusSeconds(secondsAfterNow), null);
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), details, List.of());

        assertThatThrownBy(() -> plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void BR32_aPlanWithoutExpiry_orWithALaterOne_isActivated() {
        TradingPlan none = TradingPlan.draft(
                USER, PAIR, PlanFixture.futuresLong().build(), new PlanDetails(EntryType.LIMIT, null, null), List.of());
        TradingPlan inASecond = TradingPlan.draft(
                USER,
                PAIR,
                PlanFixture.futuresLong().build(),
                new PlanDetails(EntryType.LIMIT, NOW.plusSeconds(1), null),
                List.of());

        none.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
        inASecond.activate(PlanFixture.futuresLong().build(), List.of(), NOW);

        assertThat(none.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(inASecond.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    }

    static Stream<Arguments> otherTerms() {
        return Stream.of(
                Arguments.of("market", PlanFixture.spot()),
                Arguments.of("direction", PlanFixture.futuresShort()),
                Arguments.of("stop loss", PlanFixture.futuresLong().stop("94")),
                Arguments.of("take profit", PlanFixture.futuresLong().takeProfit("111")),
                Arguments.of("capital", PlanFixture.futuresLong().capital("2000")),
                Arguments.of("risk %", PlanFixture.futuresLong().riskPercent("0.5")),
                Arguments.of("leverage", PlanFixture.futuresLong().leverage(4)),
                Arguments.of("LIMIT entry", PlanFixture.futuresLong().entry("101")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTerms")
    void BR31_activation_recalculatesTheStoredPlan_notOneWithOtherValues(String field, PlanFixture other) {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of());

        assertThatThrownBy(() -> plan.activate(other.build(), List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not for plan");
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void BR31_aMarketPlan_isActivatedAtTheLastPrice() {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), MARKET, List.of());
        PlanCalculation atLastPrice = PlanFixture.futuresLong().entry("101").build();

        plan.activate(atLastPrice, List.of(), NOW);

        assertThat(plan.getEntryPrice()).isEqualByComparingTo("101");
        assertThat(plan.getSnapshot()).isEqualTo(PlanSnapshot.of(atLastPrice));
    }

    @Test
    void BR21_aSpotPlan_isActivated() {
        TradingPlan plan = TradingPlan.draft(USER, PAIR, PlanFixture.spot().build(), LIMIT, List.of());

        plan.activate(PlanFixture.spot().build(), List.of(), NOW);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    }

    // ------------------------------------------------------------------------------------------
    // The lifecycle (BR-32)
    // ------------------------------------------------------------------------------------------

    /** Every pair whose target has a method; no method leads back to DRAFT (an edit keeps a DRAFT a DRAFT). */
    static Stream<Arguments> everyTransition() {
        return Stream.of(PlanStatus.values()).flatMap(from -> Stream.of(PlanStatus.values())
                .filter(to -> to != PlanStatus.DRAFT)
                .map(to -> Arguments.of(from, to)));
    }

    /** The pairs of the table, driven through the aggregate's own methods. */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyTransition")
    void BR32_theAggregate_followsTheTransitionTable(PlanStatus from, PlanStatus to) {
        TradingPlan plan = planIn(from);
        Consumer<TradingPlan> move = moveTo(to);

        if (from.canTransitionTo(to)) {
            move.accept(plan);
            assertThat(plan.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> move.accept(plan))
                    .isInstanceOf(IllegalPlanStateException.class)
                    .hasMessageContaining("is " + from);
            assertThat(plan.getStatus()).isEqualTo(from);
        }
    }

    @Test
    void BR32_eachTransition_recordsTheInstantItWasGiven() {
        TradingPlan executed = planIn(PlanStatus.ACTIVE);
        executed.markExecuted(LATER, new BigDecimal("99.5"));
        TradingPlan cancelledDraft = planIn(PlanStatus.DRAFT);
        cancelledDraft.cancel(LATER);
        TradingPlan cancelledActive = planIn(PlanStatus.ACTIVE);
        cancelledActive.cancel(LATER);
        TradingPlan expired = planIn(PlanStatus.ACTIVE);
        expired.expire(EXPIRY.plusSeconds(1));

        assertThat(executed.getActivatedAt()).isEqualTo(NOW);
        assertThat(executed.getExecutedAt()).isEqualTo(LATER);
        assertThat(executed.getFillPrice()).isEqualByComparingTo("99.5");
        assertThat(cancelledDraft.getActivatedAt()).isNull();
        assertThat(cancelledDraft.getCancelledAt()).isEqualTo(LATER);
        assertThat(cancelledActive.getCancelledAt()).isEqualTo(LATER);
        assertThat(expired.getExpiredAt()).isEqualTo(EXPIRY.plusSeconds(1));
        assertThat(List.of(executed, cancelledDraft, cancelledActive, expired))
                .as("the audit instants are the listener's, written on save")
                .allSatisfy(plan -> assertThat(plan.getUpdatedAt()).isNull());
    }

    /**
     * The column keeps microseconds: an activation 500 ns after a minute opened is stored as the minute itself, so the
     * activation guard (D-77) decides the same live and after a restart.
     */
    @Test
    void D77_theActivationInstant_isKeptToTheMicrosecond_likeTheColumn() {
        TradingPlan plan = planIn(PlanStatus.DRAFT);
        Instant minute = Instant.parse("2026-10-01T08:01:00Z");

        plan.activate(PlanFixture.futuresLong().build(), List.of(), minute.plusNanos(500));
        plan.markExecuted(minute.plusSeconds(1).plusNanos(1_999), BigDecimal.ONE);

        assertThat(plan.getActivatedAt()).isEqualTo(minute);
        assertThat(plan.getExecutedAt()).isEqualTo(minute.plusSeconds(1).plusNanos(1_000));
    }

    @Test
    void BR33_aPlanIsExecuted_onlyWithItsFillPrice() {
        TradingPlan plan = planIn(PlanStatus.ACTIVE);

        assertThatThrownBy(() -> plan.markExecuted(LATER, null)).isInstanceOf(NullPointerException.class);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.getFillPrice()).isNull();
    }

    @Test
    void BR32_aPlanExpires_exactlyAtItsExpiry() {
        TradingPlan plan = planIn(PlanStatus.ACTIVE);

        plan.expire(EXPIRY);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.EXPIRED);
        assertThat(plan.getExpiredAt()).isEqualTo(EXPIRY);
    }

    @Test
    void BR32_aPlanDoesNotExpire_beforeItsExpiry() {
        TradingPlan plan = planIn(PlanStatus.ACTIVE);

        assertThatThrownBy(() -> plan.expire(EXPIRY.minusSeconds(1))).isInstanceOf(IllegalPlanStateException.class);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.ACTIVE);
    }

    @Test
    void BR32_aPlanWithoutExpiry_neverExpires() {
        TradingPlan plan = TradingPlan.draft(
                USER, PAIR, PlanFixture.futuresLong().build(), new PlanDetails(EntryType.LIMIT, null, null), List.of());
        plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW);

        assertThatThrownBy(() -> plan.expire(EXPIRY.plus(Duration.ofDays(365))))
                .isInstanceOf(IllegalPlanStateException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = PlanStatus.class,
            names = {"EXECUTED", "CANCELLED", "EXPIRED"})
    void BR31_theSnapshot_isFixedOnceThePlanIsActivated(PlanStatus end) {
        TradingPlan plan = planIn(PlanStatus.ACTIVE);
        PlanSnapshot activated = plan.getSnapshot();
        List<PlanWarning> warnings = plan.getWarnings();

        moveTo(end).accept(plan);
        assertThatThrownBy(() ->
                        plan.updateDraft(PlanFixture.futuresLong().stop("90").build(), LIMIT, List.of()))
                .isInstanceOf(IllegalPlanStateException.class);
        assertThatThrownBy(() -> plan.activate(PlanFixture.futuresLong().build(), List.of(), LATER))
                .isInstanceOf(IllegalPlanStateException.class);

        assertThat(plan.getSnapshot()).isEqualTo(activated);
        assertThat(plan.getWarnings()).isEqualTo(warnings);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** A Futures LONG plan brought to {@code status} through the aggregate's own methods. */
    private static TradingPlan planIn(PlanStatus status) {
        TradingPlan plan =
                TradingPlan.draft(USER, PAIR, PlanFixture.futuresLong().build(), LIMIT, List.of(WARNING));
        if (status == PlanStatus.DRAFT) {
            return plan;
        }
        if (status == PlanStatus.CANCELLED) {
            plan.cancel(NOW);
            return plan;
        }
        plan.activate(PlanFixture.futuresLong().build(), List.of(INFO), NOW);
        if (status != PlanStatus.ACTIVE) {
            moveTo(status).accept(plan);
        }
        return plan;
    }

    /** The aggregate method that leads to {@code status}. */
    private static Consumer<TradingPlan> moveTo(PlanStatus status) {
        return switch (status) {
            case DRAFT -> throw new IllegalArgumentException("no method leads to DRAFT");
            case ACTIVE -> plan -> plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
            case EXECUTED -> plan -> plan.markExecuted(LATER, BigDecimal.TEN);
            case CANCELLED -> plan -> plan.cancel(LATER);
            case EXPIRED -> plan -> plan.expire(EXPIRY);
        };
    }

    private static Arguments rejected(String rule, PlanCalculation calculation, String field, String messageCode) {
        return Arguments.of(rule, calculation, Map.of(field, messageCode));
    }

    /** VALIDATION_FAILED (400, MSG01) naming exactly these fields with their message codes. */
    private static void assertValidationFailed(Throwable thrown, Map<String, String> errors) {
        assertThat(thrown).isInstanceOf(FieldValidationException.class);
        FieldValidationException invalid = (FieldValidationException) thrown;
        assertThat(invalid.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(invalid.errors()).isEqualTo(errors);
    }

    /** {@code base} with other inputs and the same sizing, as only a calculation that was skipped could produce. */
    private static PlanCalculation with(
            PlanCalculation base, Direction direction, String entry, String stop, String takeProfit, int leverage) {
        RiskInput in = base.plan();
        RiskInput changed = new RiskInput(
                in.market(),
                direction,
                new BigDecimal(entry),
                new BigDecimal(stop),
                new BigDecimal(takeProfit),
                in.capital(),
                in.riskPercent(),
                leverage,
                in.filters(),
                in.profile(),
                in.otherOpenRisk());
        return new PlanCalculation(changed, base.sized(), base.liquidation(), base.fundingRate(), base.thresholds());
    }
}

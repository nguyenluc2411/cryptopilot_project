package com.cryptopilot.trading.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedLevelChange;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.PositionLevelRule;
import com.cryptopilot.trading.calculator.warning.WidenedStopRule;
import com.cryptopilot.trading.dto.request.ChangePositionLevelsRequest;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.PositionLevelsChanged;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.PositionLevelService;
import com.cryptopilot.user.PlanDefaults;
import com.cryptopilot.user.RiskProfileApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes the stop loss and take profit of the OPEN position of a Trader's executed plan (D-124). The new levels are
 * rounded to the pair's tick and checked against the current last price, never the mark price; the change is a
 * compare-and-set on the journal row's version through {@link TradeJournal}, so a change racing an exit (take profit,
 * stop loss, liquidation or a manual close) has one outcome: the writer that commits first wins and the other changes
 * nothing. After the commit the matching engine replaces the levels in its books ({@link PositionLevelsChanged}).
 *
 * <ul>
 *   <li>The change runs under the D-63 lock of the Trader: a stop moved away raises the open risk that the Trader's
 *       next activation is checked against (BR-29).
 *   <li>A stop loss moved further from the entry than the current one is checked against the profile's limits and
 *       answers non-blocking warnings; it is stored anyway (Q-79).
 *   <li>{@code stop_widened} is set when the new stop loss is further from the entry than the ORIGINAL one; the
 *       realized R keeps the original planned risk (BR-42).
 *   <li>Exit pricing does not change (D-122): a moved stop loss exits as a taker with slippage, a moved take profit as
 *       a resting maker order at its price.
 * </ul>
 *
 * <p>Rule: BR-22, BR-28, BR-29, BR-30, BR-35, BR-42; UC-21; D-63, D-119 (5), D-122, D-124; Q-79.
 *
 * <p>Reference: Herlihy, M. &amp; Shavit, N. (2008). <i>The Art of Multiprocessor Programming</i>. Morgan Kaufmann,
 * ch. 5 (compare-and-set: one of several concurrent callers succeeds, the others observe the new state).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionLevelServiceImpl implements PositionLevelService {

    private static final Logger log = LoggerFactory.getLogger(PositionLevelServiceImpl.class);

    /** Excludes no plan from the ACTIVE risk: no row has this key. */
    private static final UUID NO_PLAN = new UUID(0L, 0L);

    private final TradingPlanRepository plans;
    private final TradeJournal journal;
    private final MarketApi market;
    private final RiskProfileApi riskProfiles;
    private final UserLock userLock;
    private final PositionViews positionViews;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public TradingPlanResponse changeLevels(UUID userId, UUID planId, ChangePositionLevelsRequest request) {
        if (request.stopLoss() == null && request.takeProfit() == null) {
            throw new FieldValidationException(
                    "a change of the levels needs a stop loss or a take profit", Map.of("stopLoss", "MSG01"));
        }
        userLock.lock(userId);
        TradingPlan plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingPlan", planId));
        OpenTrade trade = openTrade(plan);
        TradablePair pair = market.tradablePair(plan.getPairId(), plan.getMarket())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", plan.getPairId()));
        BigDecimal newStopLoss = changed(pair, request.stopLoss(), trade.stopLoss());
        BigDecimal newTakeProfit = changed(pair, request.takeProfit(), trade.takeProfit());
        if (newStopLoss == null && newTakeProfit == null) {
            return PlanResponses.detail(plan, positionViews.of(plan));
        }
        BigDecimal last = market.currentLastPrice(plan.getMarket(), pair.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE,
                        "no current last price for " + pair.symbol() + " on " + plan.getMarket()
                                + " to check the new levels of plan " + planId));
        requireNone(PositionLevelRule.violations(
                plan.getDirection(), newStopLoss, newTakeProfit, last, trade.liquidationPrice()));
        BigDecimal stopLoss = newStopLoss != null ? newStopLoss : trade.stopLoss();
        BigDecimal takeProfit = newTakeProfit != null ? newTakeProfit : trade.takeProfit();
        // To the microsecond, like the column, so the engine and a replay rebuilt from the history agree.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SimulatedLevelChange change = new SimulatedLevelChange(
                trade.positionId(),
                trade.version(),
                trade.stopLoss(),
                trade.takeProfit(),
                stopLoss,
                takeProfit,
                PositionLevelRule.isFurtherFromEntry(plan.getDirection(), stopLoss, plan.getStopLoss()),
                now);
        OpenTrade updated = journal.changeSimulatedLevels(change)
                .orElseThrow(() -> new IllegalPlanStateException(
                        planId, plan.getStatus(), "change the levels of a position that closed meanwhile"));
        events.publishEvent(new PositionLevelsChanged(TrackedPosition.of(updated), change.history()));
        log.info(
                "D-124 plan {} position {} levels: stop loss {} -> {}, take profit {} -> {}",
                planId,
                trade.positionId(),
                trade.stopLoss(),
                stopLoss,
                trade.takeProfit(),
                takeProfit);
        List<PlanWarning> warnings =
                PositionLevelRule.isFurtherFromEntry(plan.getDirection(), stopLoss, trade.stopLoss())
                        ? widenedStopWarnings(userId, updated)
                        : List.of();
        // Read again: the compare-and-set cleared the persistence context, and the response reads the plan's warnings.
        TradingPlan stored = plans.findById(planId).orElseThrow();
        RecordedPosition recorded = journal.positionOf(planId).orElseThrow();
        return PlanResponses.detail(stored, positionViews.of(stored, recorded, warnings));
    }

    /** The OPEN position of an EXECUTED plan; any other plan, or a closed position, answers MSG43. */
    private OpenTrade openTrade(TradingPlan plan) {
        if (plan.getStatus() != PlanStatus.EXECUTED) {
            throw new IllegalPlanStateException(plan.getId(), plan.getStatus(), "change the levels of its position");
        }
        return journal.openSimulatedTrades(plan.getUserId()).stream()
                .filter(trade -> trade.planId().equals(plan.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalPlanStateException(
                        plan.getId(), plan.getStatus(), "change the levels of a position that is not OPEN"));
    }

    /** The requested level on the pair's tick (BR-30), or {@code null} when not requested or equal to the current. */
    private static BigDecimal changed(TradablePair pair, BigDecimal requested, BigDecimal current) {
        if (requested == null) {
            return null;
        }
        BigDecimal rounded = pair.filters().roundPrice(requested);
        return current != null && rounded.compareTo(current) == 0 ? null : rounded;
    }

    /**
     * The warnings of a stop moved away from the entry: the position's risk at its new stop and the Trader's open risk
     * with it, against the risk profile, over the profile's default capital. None when the profile has no capital to
     * divide by.
     *
     * <p>Rule: BR-29; D-121, D-124.
     */
    private List<PlanWarning> widenedStopWarnings(UUID userId, OpenTrade updated) {
        PlanDefaults defaults = riskProfiles.planDefaultsOf(userId);
        BigDecimal capital = defaults.defaultCapital();
        if (capital == null || capital.signum() <= 0) {
            return List.of();
        }
        BigDecimal positionRisk = PositionLevelRule.currentRisk(
                updated.direction(), updated.entryPrice(), updated.stopLoss(), updated.quantity());
        BigDecimal totalOpenRisk = plans.activeRiskExcluding(userId, NO_PLAN)
                .add(TradingPlanServiceImpl.currentRisk(journal.openSimulatedTrades(userId)));
        return WidenedStopRule.warnings(
                positionRisk, totalOpenRisk, capital, TradingPlanServiceImpl.limits(defaults.riskProfile()));
    }

    private static void requireNone(List<InputViolation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        violations.forEach(violation -> errors.putIfAbsent(violation.field(), violation.messageCode()));
        throw new FieldValidationException("the new levels of the position are rejected: " + violations, errors);
    }
}

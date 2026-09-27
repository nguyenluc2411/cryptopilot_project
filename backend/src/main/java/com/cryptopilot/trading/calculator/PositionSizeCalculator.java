package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.trading.Direction;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.RiskOutcome;
import com.cryptopilot.trading.model.RiskProfileLimits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Sizes a plan from the risk the Trader accepts (fixed fractional sizing): the risk budget is a fixed percentage of
 * capital, and the quantity is the budget over the distance to the stop loss, floored to the pair's step size so the
 * risk never exceeds the budget.
 *
 * <p>Every input is checked first and all violations are reported together (BR-21, BR-22, BR-30), so no division
 * runs on a zero stop distance. A quantity that floors to 0 or a notional below the pair's minimum is refused with
 * MSG15 (SRS 3.5.1). Arithmetic is {@code BigDecimal} throughout; divisions use {@code DECIMAL128}; nothing is
 * rounded for display (CR-03). Prices are expected on the tick size and are not rounded here.
 *
 * <p>Margin, the leverage bracket and the liquidation price (BR-26, BR-27) and the warnings (BR-28, BR-29) are
 * separate calculations; this one exposes the three comparisons with the risk profile they report.
 *
 * <p>Rule: BR-21, BR-22, BR-23, BR-24, BR-25, BR-30; SRS 3.5.1; CR-03; TECHNICAL_DESIGN 5.4 and 7.5; ADR-008; D-53.
 * <p>Reference: Vince, R. (1990). <i>Portfolio Management Formulas: Mathematical Trading Methods for the Futures,
 * Options, and Stock Markets</i>. Wiley (fixed fractional position sizing).
 * <p>Reference: Tharp, V. K. (1998). <i>Trade Your Way to Financial Freedom</i>. McGraw-Hill (position sizing by the
 * risk per trade; the risk amount as 1R).
 */
public final class PositionSizeCalculator {

    static final BigDecimal MIN_RISK_PERCENT = new BigDecimal("0.1");
    static final BigDecimal MAX_RISK_PERCENT = BigDecimal.TEN;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String OUT_OF_RANGE = "MSG15";
    private static final String PRICE_ORDER = "MSG16";
    private static final String NOT_ALLOWED = "MSG01";

    private PositionSizeCalculator() {}

    /** Sizes the plan, or reports every value that prevents it. */
    public static RiskOutcome calculate(RiskInput in) {
        List<InputViolation> violations = validate(in);
        if (!violations.isEmpty()) {
            return new RiskInputRejected(violations);
        }
        PairFilters filters = in.filters();
        BigDecimal stopDistance = in.entryPrice().subtract(in.stopLoss()).abs();
        BigDecimal riskBudget = Rounding.divide(in.capital().multiply(in.riskPercent()), HUNDRED);
        BigDecimal quantity = filters.floorQuantity(Rounding.divide(riskBudget, stopDistance));
        if (quantity.signum() == 0) {
            return reject("quantity", OUT_OF_RANGE, "the risk budget buys less than one step of " + filters.stepSize());
        }
        BigDecimal notional = quantity.multiply(in.entryPrice());
        if (filters.isBelowMinNotional(notional)) {
            return reject(
                    "notional",
                    OUT_OF_RANGE,
                    "the notional " + notional + " is below the pair's minimum of " + filters.minNotional());
        }
        BigDecimal riskAmount = quantity.multiply(stopDistance);
        BigDecimal rewardAmount =
                quantity.multiply(in.takeProfit().subtract(in.entryPrice()).abs());
        RiskProfileLimits profile = in.profile();
        BigDecimal totalOpenRiskPercent =
                Rounding.divide(riskAmount.add(in.otherOpenRisk()).multiply(HUNDRED), in.capital());
        return new RiskCalculation(
                riskBudget,
                quantity,
                notional,
                riskAmount,
                rewardAmount,
                Rounding.divide(rewardAmount, riskAmount),
                in.market() == MarketType.SPOT && notional.compareTo(in.capital()) > 0,
                in.riskPercent().compareTo(profile.riskPerTradePercent()) > 0,
                in.leverage() > profile.maxLeverage(),
                totalOpenRiskPercent,
                totalOpenRiskPercent.compareTo(profile.maxTotalOpenRiskPercent()) > 0);
    }

    private static List<InputViolation> validate(RiskInput in) {
        List<InputViolation> violations = new ArrayList<>();
        if (in.market() == MarketType.SPOT && in.direction() != Direction.LONG) {
            violations.add(new InputViolation("direction", NOT_ALLOWED, "a Spot plan is LONG only (BR-21)"));
        }
        if (in.market() == MarketType.SPOT && in.leverage() != 1) {
            violations.add(new InputViolation("leverage", NOT_ALLOWED, "a Spot plan has no leverage (BR-21)"));
        }
        if (in.leverage() < 1) {
            violations.add(
                    new InputViolation("leverage", OUT_OF_RANGE, "leverage is at least 1, was " + in.leverage()));
        }
        if (in.capital().signum() <= 0) {
            violations.add(new InputViolation("capital", OUT_OF_RANGE, "capital must be above 0, was " + in.capital()));
        }
        if (in.riskPercent().compareTo(MIN_RISK_PERCENT) < 0 || in.riskPercent().compareTo(MAX_RISK_PERCENT) > 0) {
            violations.add(new InputViolation(
                    "riskPercent", OUT_OF_RANGE, "risk % is between 0.1 and 10, was " + in.riskPercent()));
        }
        boolean pricesValid = price(violations, "entryPrice", in.entryPrice(), in.filters())
                & price(violations, "stopLoss", in.stopLoss(), in.filters())
                & price(violations, "takeProfit", in.takeProfit(), in.filters());
        if (pricesValid) {
            priceOrder(violations, in);
        }
        return violations;
    }

    /** BR-30: above 0 and a multiple of the tick size. */
    private static boolean price(List<InputViolation> violations, String field, BigDecimal price, PairFilters filters) {
        if (price.signum() <= 0) {
            violations.add(new InputViolation(field, OUT_OF_RANGE, field + " must be above 0, was " + price));
            return false;
        }
        if (price.remainder(filters.tickSize()).signum() != 0) {
            violations.add(new InputViolation(
                    field,
                    OUT_OF_RANGE,
                    field + " " + price + " is not a multiple of the tick size " + filters.tickSize()));
            return false;
        }
        return true;
    }

    /** BR-22: LONG stop loss < entry < take profit; SHORT take profit < entry < stop loss. */
    private static void priceOrder(List<InputViolation> violations, RiskInput in) {
        int side = in.direction() == Direction.LONG ? 1 : -1;
        if (in.entryPrice().subtract(in.stopLoss()).signum() * side <= 0) {
            violations.add(new InputViolation(
                    "stopLoss", PRICE_ORDER, "the stop loss is on the wrong side of the entry for " + in.direction()));
        }
        if (in.takeProfit().subtract(in.entryPrice()).signum() * side <= 0) {
            violations.add(new InputViolation(
                    "takeProfit",
                    PRICE_ORDER,
                    "the take profit is on the wrong side of the entry for " + in.direction()));
        }
    }

    private static RiskInputRejected reject(String field, String messageCode, String detail) {
        return new RiskInputRejected(List.of(new InputViolation(field, messageCode, detail)));
    }
}

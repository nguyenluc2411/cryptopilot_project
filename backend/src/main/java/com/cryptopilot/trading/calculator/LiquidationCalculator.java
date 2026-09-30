package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.LiquidationOutcome;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Estimates the margin and the liquidation price of an isolated, one-way Futures position, fees ignored. The
 * notional selects the leverage bracket, whose maximum limits the leverage (BR-26); the position is liquidated where
 * its wallet balance, the initial margin, plus the unrealized profit and loss falls to the maintenance margin of the
 * bracket (BR-27). The maintenance amount {@code cum} of the bracket is part of the formula (A-01), which keeps the
 * maintenance margin continuous where one bracket ends and the next begins; with {@code cum = 0} the formula is the
 * simplified one. The liquidation price is rounded to the tick towards the position, so the estimate never looks
 * safer than it is, and compared with the stop loss (BR-28).
 *
 * <p>Arithmetic is {@code BigDecimal} throughout; divisions use {@code DECIMAL128}, as in the sizing of T-035.
 *
 * <p>Rule: BR-26, BR-27, BR-28; SCR-38; CR-03; TECHNICAL_DESIGN 5.4, 7.5 and 7.6; ADR-008; A-01.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (margin accounts, maintenance margin and the margin call).
 * <p>Reference: Binance. <i>USDⓈ-M Futures: How to Calculate Liquidation Price</i> and <i>Leverage and Margin of
 * USDⓈ-M Futures</i> (leverage brackets, maintenance margin rate and maintenance amount), Binance Support.
 */
public final class LiquidationCalculator {

    private static final String OUT_OF_RANGE = "MSG15";

    private LiquidationCalculator() {}

    /** Estimates the margin and the liquidation price, or reports the leverage or notional that prevents it. */
    public static LiquidationOutcome calculate(LiquidationInput in) {
        BigDecimal quantity = in.quantity();
        BigDecimal notional = quantity.multiply(in.entryPrice());
        LeverageBracket bracket = withMaintenanceAmounts(in.brackets()).stream()
                .filter(b -> b.contains(notional))
                .findFirst()
                .orElse(null);
        if (bracket == null) {
            return reject("notional", "the notional " + notional + " is above the largest leverage bracket");
        }
        if (in.leverage() > bracket.maxLeverage()) {
            return reject(
                    "leverage",
                    "leverage is between 1 and " + bracket.maxLeverage() + " in bracket " + bracket.bracketNo()
                            + ", was " + in.leverage());
        }
        BigDecimal mmr = bracket.maintenanceMarginRate();
        BigDecimal cum = bracket.maintenanceAmount();
        BigDecimal initialMargin = Rounding.divide(notional, BigDecimal.valueOf(in.leverage()));
        // Maintenance margin of the bracket: MM = N × MMR − cum.
        BigDecimal maintenanceMargin = notional.multiply(mmr).subtract(cum);

        // Liquidated where WB + Q × (LP − E) = Q × LP × MMR − cum for LONG, and the mirror for SHORT:
        //   LONG:  LP = (Q × E − WB − cum) / (Q × (1 − MMR))
        //   SHORT: LP = (Q × E + WB + cum) / (Q × (1 + MMR))
        BigDecimal liquidationPrice;
        boolean stopBeyondLiquidation;
        if (in.direction() == Direction.LONG) {
            BigDecimal raw = Rounding.divide(
                    notional.subtract(initialMargin).subtract(cum), quantity.multiply(BigDecimal.ONE.subtract(mmr)));
            // At or below 0 no price can liquidate the position (1x with a maintenance amount).
            liquidationPrice =
                    raw.signum() <= 0 ? BigDecimal.ZERO : Rounding.toTickSize(raw, in.tickSize(), RoundingMode.CEILING);
            stopBeyondLiquidation = in.stopLoss().compareTo(liquidationPrice) <= 0;
        } else {
            BigDecimal raw =
                    Rounding.divide(notional.add(initialMargin).add(cum), quantity.multiply(BigDecimal.ONE.add(mmr)));
            liquidationPrice = Rounding.toTickSize(raw, in.tickSize(), RoundingMode.FLOOR);
            stopBeyondLiquidation = in.stopLoss().compareTo(liquidationPrice) >= 0;
        }
        return new LiquidationEstimate(
                bracket,
                notional,
                initialMargin,
                maintenanceMargin,
                liquidationPrice,
                initialMargin.compareTo(in.capital()) > 0,
                stopBeyondLiquidation);
    }

    /**
     * The brackets in notional order, each with a maintenance amount: the one the source gives, or else the amount
     * that makes the maintenance margin meet the bracket below at its floor, {@code cum_1 = 0} and
     * {@code cum_n = cum_(n-1) + floor_n × (MMR_n − MMR_(n-1))}.
     */
    static List<LeverageBracket> withMaintenanceAmounts(List<LeverageBracket> brackets) {
        List<LeverageBracket> sorted = brackets.stream()
                .sorted(Comparator.comparing(LeverageBracket::notionalFloor))
                .toList();
        List<LeverageBracket> resolved = new ArrayList<>(sorted.size());
        LeverageBracket below = null;
        for (LeverageBracket bracket : sorted) {
            if (bracket.maintenanceAmount() == null) {
                BigDecimal amount = below == null
                        ? BigDecimal.ZERO
                        : below.maintenanceAmount()
                                .add(bracket.notionalFloor()
                                        .multiply(bracket.maintenanceMarginRate()
                                                .subtract(below.maintenanceMarginRate())));
                bracket = bracket.withMaintenanceAmount(amount);
            }
            resolved.add(bracket);
            below = bracket;
        }
        return resolved;
    }

    private static RiskInputRejected reject(String field, String detail) {
        return new RiskInputRejected(List.of(new InputViolation(field, OUT_OF_RANGE, detail)));
    }
}

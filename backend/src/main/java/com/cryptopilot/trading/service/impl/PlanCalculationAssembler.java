package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.LiquidationCalculator;
import com.cryptopilot.trading.calculator.PositionSizeCalculator;
import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.model.CalculatedPlan;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.LiquidationOutcome;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.RiskOutcome;
import com.cryptopilot.trading.model.WarningThresholds;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs a plan through the existing calculations — sizing (T-035), liquidation estimate (T-036), warning rules
 * (T-037) — with the data they need from other modules: the pair's leverage brackets and funding rate, and the BR-29
 * thresholds the risk profile does not define. It decides nothing itself; a value the calculations reject is reported
 * per field (MSG01, MSG15, MSG16).
 *
 * <p>Rule: BR-23 to BR-29; TECHNICAL_DESIGN 7.5; D-70.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PlanCalculationAssembler {

    static final String LOW_RR = "WARN_LOW_RR_RATIO";
    static final String WIDE_STOP_LOSS = "WARN_WIDE_STOP_LOSS_PERCENT";
    static final String HIGH_FUNDING_RATE = "WARN_HIGH_FUNDING_RATE";

    private final MarketApi market;
    private final SystemSettingApi settings;

    /** The plan calculated and its warnings, or a {@link FieldValidationException} naming the rejected fields. */
    public CalculatedPlan calculate(TradablePair pair, RiskInput input) {
        RiskOutcome outcome = PositionSizeCalculator.calculate(input);
        if (outcome instanceof RiskInputRejected rejected) {
            throw rejected(rejected);
        }
        RiskCalculation sized = (RiskCalculation) outcome;
        LiquidationEstimate liquidation = null;
        BigDecimal fundingRate = null;
        if (input.market() == MarketType.FUTURES) {
            liquidation = liquidation(pair, input, sized);
            fundingRate = market.currentFundingRate(pair.symbol()).orElse(null);
        }
        PlanCalculation calculation = new PlanCalculation(input, sized, liquidation, fundingRate, thresholds());
        return new CalculatedPlan(calculation, WarningEvaluator.evaluate(calculation));
    }

    private LiquidationEstimate liquidation(TradablePair pair, RiskInput input, RiskCalculation sized) {
        List<LeverageBracket> brackets = market.leverageBrackets(pair.pairId()).stream()
                .map(PlanCalculationAssembler::bracket)
                .toList();
        if (brackets.isEmpty()) {
            // The bracket sets the leverage range; without one no Futures plan can be sized (D-70).
            throw new FieldValidationException(
                    "pair " + pair.symbol() + " has no leverage brackets", Map.of("leverage", "MSG15"));
        }
        LiquidationOutcome outcome = LiquidationCalculator.calculate(LiquidationInput.of(input, sized, brackets));
        if (outcome instanceof RiskInputRejected rejected) {
            throw rejected(rejected);
        }
        return (LiquidationEstimate) outcome;
    }

    private WarningThresholds thresholds() {
        return new WarningThresholds(
                settings.decimal(LOW_RR), settings.decimal(WIDE_STOP_LOSS), settings.decimal(HIGH_FUNDING_RATE));
    }

    private static LeverageBracket bracket(LeverageTier tier) {
        return new LeverageBracket(
                tier.bracketNo(),
                tier.notionalFloor(),
                tier.notionalCap(),
                tier.maxLeverage(),
                tier.maintenanceMarginRate(),
                tier.maintenanceAmount());
    }

    private static FieldValidationException rejected(RiskInputRejected rejected) {
        return new FieldValidationException(
                "the calculation rejects the plan's inputs: " + rejected.violations(), rejected.fieldErrors());
    }
}

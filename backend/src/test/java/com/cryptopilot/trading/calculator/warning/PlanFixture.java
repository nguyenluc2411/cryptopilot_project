package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.LiquidationCalculator;
import com.cryptopilot.trading.calculator.PositionSizeCalculator;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.LiquidationOutcome;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskOutcome;
import com.cryptopilot.trading.model.RiskProfileLimits;
import com.cryptopilot.trading.model.WarningThresholds;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.List;

/**
 * A plan run through the real sizing (T-035) and liquidation estimate (T-036), so each rule is tested on the values
 * those calculations produce. The default is a Futures LONG that raises no warning: E 100, S 95, T 110, capital
 * 1,000, risk 1 %, 5x, a BALANCED profile (1 %, 5x, 4 %) and the seeded thresholds (1.5, 10 %, 0.001).
 */
final class PlanFixture {

    static final List<LeverageBracket> BRACKETS = List.of(
            new LeverageBracket(1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), null),
            new LeverageBracket(
                    2, new BigDecimal("50000"), new BigDecimal("250000"), 100, new BigDecimal("0.005"), null),
            new LeverageBracket(
                    3, new BigDecimal("250000"), new BigDecimal("1000000"), 50, new BigDecimal("0.01"), null));

    private MarketType market = MarketType.FUTURES;
    private Direction direction = Direction.LONG;
    private String entry = "100";
    private String stop = "95";
    private String takeProfit = "110";
    private String capital = "1000";
    private String riskPercent = "1";
    private int leverage = 5;
    private RiskProfileLimits profile = new RiskProfileLimits(BigDecimal.ONE, 5, new BigDecimal("4"));
    private String otherOpenRisk = "0";
    private String fundingRate;
    private WarningThresholds thresholds =
            new WarningThresholds(new BigDecimal("1.5"), BigDecimal.TEN, new BigDecimal("0.001"));

    static PlanFixture futuresLong() {
        return new PlanFixture();
    }

    static PlanFixture futuresShort() {
        return new PlanFixture().direction(Direction.SHORT).stop("105").takeProfit("90");
    }

    static PlanFixture spot() {
        PlanFixture plan = new PlanFixture();
        plan.market = MarketType.SPOT;
        plan.leverage = 1;
        return plan;
    }

    PlanFixture direction(Direction value) {
        direction = value;
        return this;
    }

    PlanFixture entry(String value) {
        entry = value;
        return this;
    }

    PlanFixture stop(String value) {
        stop = value;
        return this;
    }

    PlanFixture takeProfit(String value) {
        takeProfit = value;
        return this;
    }

    PlanFixture capital(String value) {
        capital = value;
        return this;
    }

    PlanFixture riskPercent(String value) {
        riskPercent = value;
        return this;
    }

    PlanFixture leverage(int value) {
        leverage = value;
        return this;
    }

    PlanFixture profile(String riskPerTrade, int maxLeverage) {
        return profile(riskPerTrade, maxLeverage, "6");
    }

    PlanFixture profile(String riskPerTrade, int maxLeverage, String maxTotalOpenRisk) {
        profile = new RiskProfileLimits(new BigDecimal(riskPerTrade), maxLeverage, new BigDecimal(maxTotalOpenRisk));
        return this;
    }

    /** The risk amount of the Trader's other ACTIVE plans and open positions, in USDT. */
    PlanFixture otherOpenRisk(String value) {
        otherOpenRisk = value;
        return this;
    }

    PlanFixture fundingRate(String value) {
        fundingRate = value;
        return this;
    }

    PlanFixture thresholds(String minRiskReward, String wideStopPercent, String highFunding) {
        thresholds = new WarningThresholds(
                new BigDecimal(minRiskReward), new BigDecimal(wideStopPercent), new BigDecimal(highFunding));
        return this;
    }

    PlanCalculation build() {
        RiskInput plan = new RiskInput(
                market,
                direction,
                new BigDecimal(entry),
                new BigDecimal(stop),
                new BigDecimal(takeProfit),
                new BigDecimal(capital),
                new BigDecimal(riskPercent),
                leverage,
                new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")),
                profile,
                new BigDecimal(otherOpenRisk));
        RiskOutcome sizing = PositionSizeCalculator.calculate(plan);
        assertThat(sizing).as("the fixture's plan is sized").isInstanceOf(RiskCalculation.class);
        RiskCalculation sized = (RiskCalculation) sizing;
        LiquidationEstimate liquidation = null;
        if (market == MarketType.FUTURES) {
            LiquidationOutcome estimate = LiquidationCalculator.calculate(LiquidationInput.of(plan, sized, BRACKETS));
            assertThat(estimate).as("the fixture's plan is estimated").isInstanceOf(LiquidationEstimate.class);
            liquidation = (LiquidationEstimate) estimate;
        }
        return new PlanCalculation(
                plan, sized, liquidation, fundingRate == null ? null : new BigDecimal(fundingRate), thresholds);
    }
}

package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.BreakevenCalculator;
import com.cryptopilot.trading.dto.response.PositionResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.Breakeven;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.service.FundingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The position block of an executed plan (UC-20, SCR-18): its journal record, the price its unrealized P/L is read at,
 * its levels with their history, and while it is OPEN its true breakeven stop. The plan detail and the response of a
 * change of the levels build it here, so both show the same block.
 *
 * <p>Rule: BR-34, BR-35, BR-37, BR-38; UC-20, UC-21; Q-55; D-111 E2/E3, D-119, D-124.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionViews {

    private final TradeJournal journal;
    private final MarketApi market;
    private final FeeRates feeRates;
    private final FundingService funding;
    private final SystemSettingApi settings;

    /** The block of an EXECUTED plan, or {@code null} when the plan opened no position. */
    public PositionResponse of(TradingPlan plan) {
        return journal.positionOf(plan.getId())
                .map(recorded -> of(plan, recorded, List.of()))
                .orElse(null);
    }

    /** The block of a recorded position, with the warnings of a change just made ({@code levelWarnings}). */
    public PositionResponse of(TradingPlan plan, RecordedPosition recorded, List<PlanWarning> levelWarnings) {
        PositionLevels levels = journal.levelsOf(plan.getId()).orElse(null);
        return PlanResponses.position(
                plan, recorded, currentPrice(plan, recorded), levels, breakeven(plan, recorded), levelWarnings);
    }

    /**
     * The price an open position's unrealized P/L is read at: the Futures mark price, the Spot last price (Q-55; D-111
     * E2/E3 keeps the mark price for unrealized P/L). Empty when the position is closed, or when the pair or a current
     * price is not found.
     */
    private Optional<BigDecimal> currentPrice(TradingPlan plan, RecordedPosition position) {
        if (position.status() != PositionStatus.OPEN) {
            return Optional.empty();
        }
        return market.pairListings(List.of(plan.getPairId())).stream()
                .findFirst()
                .flatMap(pair -> plan.getMarket() == MarketType.FUTURES
                        ? market.currentMarkPrice(pair.symbol())
                        : market.currentLastPrice(MarketType.SPOT, pair.symbol()));
    }

    /**
     * The true breakeven stop of an OPEN position: after the entry fee stored at the fill, the taker fee and the
     * slippage of a stop-out, and on Futures the funding settled since the fill, provisional while a settlement may be
     * missing. Empty once closed, or when the pair's tick is not known.
     *
     * <p>Rule: BR-36, BR-37, BR-38; D-124.
     */
    private Optional<Breakeven> breakeven(TradingPlan plan, RecordedPosition position) {
        if (position.status() != PositionStatus.OPEN) {
            return Optional.empty();
        }
        Optional<BigDecimal> tick = market.tradablePair(plan.getPairId(), plan.getMarket())
                .map(pair -> pair.filters().tickSize());
        if (tick.isEmpty()) {
            return Optional.empty();
        }
        FundingFee fundingSoFar = plan.getMarket() == MarketType.SPOT
                ? null
                : funding.fundingSoFar(
                        plan.getPairId(), plan.getDirection(), position.quantity(), position.entryTime());
        return BreakevenCalculator.breakevenStop(
                        plan.getMarket(),
                        plan.getDirection(),
                        plan.getFillLiquidity(),
                        position.entryPrice(),
                        position.quantity(),
                        position.entryFee(),
                        feeRates.of(plan.getMarket()),
                        settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS),
                        tick.get(),
                        fundingSoFar)
                .map(price -> new Breakeven(price, fundingSoFar != null && fundingSoFar.incomplete()));
    }
}

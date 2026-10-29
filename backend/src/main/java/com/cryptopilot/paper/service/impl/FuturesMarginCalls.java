package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.dto.response.FuturesMarginCallResponse;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The margin calls of paper Futures (TR-43, plan 4.10): when the margin ratio, maintenance margin over margin balance,
 * of an isolated position or of the cross positions together is at or above {@code margin-call-ratio} and below 1,
 * the Trader is told on {@code /user/queue/futures.marginCall} and in an in-app notice. Judged again on the database
 * when the engine's screening suggests it; the engine keeps the cooldown. At 1 or above the liquidation is told
 * instead.
 *
 * <p>Rule: TR-43; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesMarginCalls {

    private final FuturesPositionRepository positions;
    private final FuturesCrossLiquidation cross;
    private final PaperUserFeed feed;
    private final MarketApi market;
    private final PaperFuturesProperties futures;
    private final ApplicationEventPublisher events;

    /**
     * Calls the Trader if the ratio holds now.
     *
     * @param positionId the isolated position, or {@code null} for the cross positions together
     * @param mark the mark of the isolated position's pair; unused for the cross positions
     * @return whether a call was sent
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean call(PaperAccount account, UUID positionId, BigDecimal mark, Instant at) {
        if (positionId == null) {
            Optional<FuturesCrossLiquidation.CrossMargin> margin = cross.crossMargin(account.userId());
            return margin.isPresent()
                    && send(
                            account,
                            null,
                            null,
                            MarginType.CROSSED,
                            null,
                            null,
                            margin.get().maintenance(),
                            margin.get().marginBalance(),
                            at);
        }
        Optional<FuturesPosition> found = positions.findById(positionId);
        if (found.isEmpty() || !found.get().isOpen() || found.get().getMarginType() != MarginType.ISOLATED) {
            return false;
        }
        FuturesPosition position = found.get();
        BigDecimal maintenance = FuturesMarginMath.liquidationMaintenance(
                market.leverageBrackets(position.getPairId()),
                position.getQuantity().multiply(mark));
        BigDecimal balance = position.getIsolatedWalletAmount()
                .add(FuturesMarginMath.unrealizedPnl(
                        position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark));
        return send(
                account,
                symbolOf(position.getPairId()),
                position,
                MarginType.ISOLATED,
                position.getId(),
                mark,
                maintenance,
                balance,
                at);
    }

    private boolean send(
            PaperAccount account,
            String symbol,
            FuturesPosition position,
            MarginType marginType,
            UUID positionId,
            BigDecimal mark,
            BigDecimal maintenance,
            BigDecimal balance,
            Instant at) {
        if (balance.signum() <= 0) {
            return false;
        }
        BigDecimal ratio = Rounding.divide(maintenance, balance);
        if (ratio.compareTo(futures.marginCallRatio()) < 0 || ratio.compareTo(BigDecimal.ONE) >= 0) {
            return false;
        }
        feed.marginCall(
                account.userId(),
                new FuturesMarginCallResponse(
                        symbol,
                        position == null ? null : position.getPositionSide(),
                        marginType,
                        mark,
                        ratio,
                        Rounding.toAmount(maintenance),
                        Rounding.toAmount(balance),
                        at));
        events.publishEvent(new FuturesRiskNotice(
                account.userId(),
                FuturesRiskNotice.Kind.MARGIN_CALL,
                symbol,
                marginType.name(),
                positionId,
                mark,
                ratio,
                at));
        return true;
    }

    private String symbolOf(UUID pairId) {
        return market.tradablePair(pairId, MarketType.FUTURES)
                .map(TradablePair::symbol)
                .orElse(null);
    }
}

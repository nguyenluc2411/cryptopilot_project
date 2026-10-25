package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.PlanNotificationService;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.event.TradingPlanInvalidated;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts of the notifications of trading plans and their positions. A plan withdrawn by BR-59 has no SRS message,
 * so its text is written in the style of MSG22 to MSG28: one short sentence naming what happened. A closed position is
 * MSG23, a liquidated one MSG24, with {@code {pnl}} the net P/L of the journal row (Q-56). An expired plan is MSG53, an
 * invalidated one MSG54 with the reason. The source key is the plan's or the position's, so a redelivery writes
 * nothing.
 *
 * <p>Rule: BR-35, BR-38, BR-59, BR-68; NSF-09, NSF-15; Q-56.
 */
@Service
@RequiredArgsConstructor
public class PlanNotificationServiceImpl implements PlanNotificationService {

    /** The {@code {pair}} of a position whose symbol could not be read. */
    static final String UNKNOWN_PAIR = "Your";

    private final NotificationWriter writer;

    @Override
    @Transactional
    public void withdrawn(TradingPlanWithdrawn event) {
        String market = event.market() == MarketType.SPOT ? "Spot" : "Futures";
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.PLAN,
                "Plan cancelled",
                "Your plan on " + event.symbol() + " " + market
                        + " was cancelled because the market is no longer supported.",
                NotificationReference.PLAN,
                event.planId(),
                "PLAN_WITHDRAWN:" + event.planId()));
    }

    /**
     * MSG23 "{pair} position closed by {reason} at {price}. P/L: {pnl}." or MSG24 "{pair} position was liquidated at
     * {price}. Loss: {pnl}.", with the prices and amounts as plain decimals without trailing zeros.
     */
    @Override
    @Transactional
    public void positionClosed(PositionClosed event) {
        String pair = pair(event.symbol());
        boolean liquidated = event.closeReason() == CloseReason.LIQUIDATION;
        String content = liquidated
                ? pair + " position was liquidated at " + plain(event.exitPrice()) + ". Loss: " + plain(event.netPnl())
                        + "."
                : pair + " position closed by " + reason(event.closeReason()) + " at " + plain(event.exitPrice())
                        + ". P/L: " + plain(event.netPnl()) + ".";
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.PLAN,
                liquidated ? "Position liquidated" : "Position closed",
                content,
                NotificationReference.PLAN,
                event.planId(),
                "POSITION_CLOSED:" + event.positionId()));
    }

    /**
     * MSG53 "{pair} {direction} plan expired before its entry was filled."
     *
     * <p>Rule: NSF-09; MSG53.
     */
    @Override
    @Transactional
    public void expired(TradingPlanExpired event) {
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.PLAN,
                "Plan expired",
                pair(event.symbol()) + " " + event.direction() + " plan expired before its entry was filled.",
                NotificationReference.PLAN,
                event.planId(),
                "PLAN_EXPIRED:" + event.planId()));
    }

    /**
     * MSG54 "{pair} {direction} plan was invalidated before its entry was filled: {reason}.", the reason naming the
     * level reached first.
     *
     * <p>Rule: BR-68, NSF-09; MSG54; D-111 (M3).
     */
    @Override
    @Transactional
    public void invalidated(TradingPlanInvalidated event) {
        writer.write(new NewNotification(
                event.userId(),
                NotificationType.PLAN,
                "Plan invalidated",
                pair(event.symbol()) + " " + event.direction() + " plan was invalidated before its entry was filled: "
                        + invalidation(event.reason()) + ".",
                NotificationReference.PLAN,
                event.planId(),
                "PLAN_INVALIDATED:" + event.planId()));
    }

    private static String pair(String symbol) {
        return symbol == null ? UNKNOWN_PAIR : symbol;
    }

    private static String invalidation(InvalidationReason reason) {
        return switch (reason) {
            case TAKE_PROFIT_REACHED -> "the take profit was reached first";
            case STOP_LOSS_REACHED -> "the stop loss was reached first";
            case INVALIDATION_PRICE -> "the invalidation price was reached";
        };
    }

    private static String reason(CloseReason reason) {
        return reason == CloseReason.TAKE_PROFIT ? "take profit" : "stop loss";
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}

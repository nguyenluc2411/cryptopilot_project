package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.PlanNotificationService;
import com.cryptopilot.trading.event.TradingPlanWithdrawn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The text of a plan withdrawn by BR-59. The SRS has no message for it, so it is written in the style of MSG22 to
 * MSG28: one short sentence naming what happened. The source key is the plan's, so a redelivery writes nothing.
 *
 * <p>Rule: BR-59; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class PlanNotificationServiceImpl implements PlanNotificationService {

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
}

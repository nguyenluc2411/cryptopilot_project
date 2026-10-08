package com.cryptopilot.watchlist.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An alert's condition was met and the trigger is recorded: {@code trigger_count} and {@code last_triggered_at} are
 * written (SRS 3.4.4). Published inside the transaction that records the trigger, so a listener sees it only for a
 * trigger that committed.
 *
 * <p>The notification of MSG28 and its delivery on the chosen channels belong to NSF-15 (T-056), which listens to this
 * event; until it exists nobody does, and there is no JDBC event publication registry yet to keep the event across a
 * restart.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley (a domain event records something that
 * happened in the domain); Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 5 (an aggregate
 * publishes domain events when its state changes).
 *
 * @param alertId the alert
 * @param userId its owner
 * @param pairId the watched pair
 * @param symbol the pair's symbol, for the notification text
 * @param market the market the alert watches
 * @param type PRICE or INDICATOR
 * @param indicator the indicator, or {@code null} for a PRICE alert
 * @param condition the comparison that was met
 * @param threshold the threshold, or {@code null} for a line cross (D-76)
 * @param observedValue the value that met the condition: the last price for a PRICE alert
 * @param triggeredAt when the trigger was recorded
 * @param triggerCount how many times the alert has fired, this trigger included
 * @param notifyInApp always true
 * @param notifyEmail whether the Trader also wants an email
 * @param notifyPush whether the Trader also wants a push notification
 */
public record AlertTriggered(
        UUID alertId,
        UUID userId,
        UUID pairId,
        String symbol,
        MarketType market,
        AlertType type,
        AlertIndicator indicator,
        ConditionOperator condition,
        BigDecimal threshold,
        BigDecimal observedValue,
        Instant triggeredAt,
        int triggerCount,
        boolean notifyInApp,
        boolean notifyEmail,
        boolean notifyPush) {}

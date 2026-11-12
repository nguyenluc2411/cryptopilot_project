package com.cryptopilot.notification.service.impl;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The words of an alert that fired, shared by its in-app notification and its mail: MSG28, "{pair}: {condition}
 * ({value})." The pair names its market, because one symbol can be watched on Spot and on Futures. The condition uses
 * the names the alert screen shows (SCR-13). The value is what met the condition: the last price, the RSI, the funding
 * rate or the open interest change, both of these in percent (Q-30, Q-45); a MACD or EMA cross has none worth showing,
 * since what crossed zero is the gap between two lines.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4.
 */
final class AlertNotices {

    private AlertNotices() {}

    /** The title of the notification. */
    static String title(AlertTriggered event) {
        return event.type() == AlertType.PRICE ? "Price alert" : "Indicator alert";
    }

    /** MSG28. */
    static String content(AlertTriggered event) {
        String value = value(event);
        return pair(event) + ": " + condition(event) + (value == null ? "" : " (" + value + ")") + ".";
    }

    /** The pair and its market, e.g. "BTCUSDT Futures". */
    static String pair(AlertTriggered event) {
        String market = event.market() == MarketType.SPOT ? "Spot" : "Futures";
        return event.symbol() == null ? market + " pair" : event.symbol() + " " + market;
    }

    private static String condition(AlertTriggered event) {
        if (event.type() == AlertType.PRICE) {
            return "price " + comparison(event.condition(), event.threshold(), "");
        }
        boolean below = event.condition() == ConditionOperator.CROSS_BELOW;
        return switch (event.indicator()) {
            case RSI_14 -> "RSI (14) " + comparison(event.condition(), event.threshold(), "");
            case MACD_CROSS -> below ? "MACD crossed below its signal line" : "MACD crossed above its signal line";
            case EMA_CROSS -> below ? "EMA 20 crossed below EMA 50" : "EMA 20 crossed above EMA 50";
            case FUNDING_RATE -> "funding rate " + comparison(event.condition(), event.threshold(), "%");
            case OPEN_INTEREST_CHANGE ->
                "open interest change (1h) " + comparison(event.condition(), event.threshold(), "%");
        };
    }

    private static String comparison(ConditionOperator condition, BigDecimal threshold, String unit) {
        String verb =
                switch (condition) {
                    case CROSS_ABOVE -> "crossed above";
                    case CROSS_BELOW -> "crossed below";
                    case GREATER_THAN -> "is above";
                    case LESS_THAN -> "is below";
                };
        return threshold == null ? verb : verb + " " + plain(threshold) + unit;
    }

    private static String value(AlertTriggered event) {
        BigDecimal observed = event.observedValue();
        if (observed == null) {
            return null;
        }
        if (event.type() == AlertType.PRICE) {
            return plain(observed);
        }
        return switch (event.indicator()) {
            case RSI_14 -> plain(observed.setScale(2, RoundingMode.HALF_UP));
            case FUNDING_RATE, OPEN_INTEREST_CHANGE -> plain(observed) + "%";
            case MACD_CROSS, EMA_CROSS -> null;
        };
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}

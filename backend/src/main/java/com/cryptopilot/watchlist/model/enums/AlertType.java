package com.cryptopilot.watchlist.model.enums;

/**
 * What an alert watches: the last traded price of the pair, or an indicator. Stored by name in
 * {@code alert.alert_type}.
 *
 * <p>Rule: BR-17, BR-18; SRS 3.4.2.
 */
public enum AlertType {

    /** The last traded price of the selected market (BR-18). */
    PRICE,

    /** One of the indicators of {@link AlertIndicator}; needs a plan with indicator alerts (BR-62). */
    INDICATOR
}

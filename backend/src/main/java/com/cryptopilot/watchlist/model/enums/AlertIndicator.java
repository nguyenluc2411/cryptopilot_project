package com.cryptopilot.watchlist.model.enums;

/**
 * The indicators an INDICATOR alert can watch (SRS 3.4.2). Stored by name in {@code alert.indicator_name}.
 *
 * <p>Rule: SRS 3.4.2, 3.4.4; TECHNICAL_DESIGN 7.9; A-40.
 */
public enum AlertIndicator {

    /** The 14-period RSI on the alert's timeframe, compared with a level from 0 to 100. */
    RSI_14(false, false),

    /** The MACD line crossing its signal line on the alert's timeframe: the histogram changes sign. */
    MACD_CROSS(false, true),

    /** EMA20 crossing EMA50 on the alert's timeframe: their difference changes sign. */
    EMA_CROSS(false, true),

    /** The funding rate from the mark price stream. Futures only; read on 1h, set by the server (A-40). */
    FUNDING_RATE(true, false),

    /** The change of open interest over one hour, in percent. Futures only; read on 1h, set by the server (A-40). */
    OPEN_INTEREST_CHANGE(true, false);

    private final boolean futuresOnly;
    private final boolean signCross;

    AlertIndicator(boolean futuresOnly, boolean signCross) {
        this.futuresOnly = futuresOnly;
        this.signCross = signCross;
    }

    /** Whether the indicator exists only on the Futures market, which also fixes its timeframe to 1h (A-40). */
    public boolean futuresOnly() {
        return futuresOnly;
    }

    /** Whether the alert is a line crossing another, i.e. a value crossing zero, which takes no threshold. */
    public boolean signCross() {
        return signCross;
    }
}

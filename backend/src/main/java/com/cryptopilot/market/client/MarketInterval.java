package com.cryptopilot.market.client;

/**
 * The candle and statistics intervals the system asks the exchange for.
 *
 * <p>Only these, not every interval Binance offers: 15m, 1h, 4h and 1d are the analysis timeframes of
 * BR-08, 1m feeds the matching engine and the price alerts, and 5m is the collection period of the
 * futures metrics of NSF-04. Each constant carries the code the exchange expects in a query string,
 * so the wire spelling lives here and nowhere else.
 *
 * <p>Rule: BR-08; NSF-02, NSF-04; TECHNICAL_DESIGN 7.1.
 */
public enum MarketInterval {

    /** One minute. */
    ONE_MINUTE("1m"),

    /** Five minutes. */
    FIVE_MINUTES("5m"),

    /** Fifteen minutes. */
    FIFTEEN_MINUTES("15m"),

    /** One hour. */
    ONE_HOUR("1h"),

    /** Four hours. */
    FOUR_HOURS("4h"),

    /** One day. */
    ONE_DAY("1d");

    private final String code;

    MarketInterval(String code) {
        this.code = code;
    }

    /** The interval as the exchange spells it in a query string. */
    public String code() {
        return code;
    }
}

package com.cryptopilot.market.model.enums;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * A timeframe of the trading chart (TR-35): the sixteen candle intervals of the exchange's chart, 1s on Spot only. Four
 * of them are stored (BR-08) and read from the database; the others are read from the exchange.
 *
 * <p>Rule: TR-35; BR-08.
 */
public enum ChartTimeframe {
    ONE_SECOND("1s", Duration.ofSeconds(1), null),
    ONE_MINUTE("1m", Duration.ofMinutes(1), null),
    THREE_MINUTES("3m", Duration.ofMinutes(3), null),
    FIVE_MINUTES("5m", Duration.ofMinutes(5), null),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), MarketInterval.FIFTEEN_MINUTES),
    THIRTY_MINUTES("30m", Duration.ofMinutes(30), null),
    ONE_HOUR("1h", Duration.ofHours(1), MarketInterval.ONE_HOUR),
    TWO_HOURS("2h", Duration.ofHours(2), null),
    FOUR_HOURS("4h", Duration.ofHours(4), MarketInterval.FOUR_HOURS),
    SIX_HOURS("6h", Duration.ofHours(6), null),
    EIGHT_HOURS("8h", Duration.ofHours(8), null),
    TWELVE_HOURS("12h", Duration.ofHours(12), null),
    ONE_DAY("1d", Duration.ofDays(1), MarketInterval.ONE_DAY),
    THREE_DAYS("3d", Duration.ofDays(3), null),
    ONE_WEEK("1w", Duration.ofDays(7), null),
    /** Calendar months; its length is the longest month's, for bounds only. */
    ONE_MONTH("1M", Duration.ofDays(31), null);

    private final String code;
    private final Duration length;
    private final MarketInterval stored;

    ChartTimeframe(String code, Duration length, MarketInterval stored) {
        this.code = code;
        this.length = length;
        this.stored = stored;
    }

    /** The interval as the exchange spells it, e.g. {@code 1M}; case matters ({@code 1m} is a minute). */
    public String code() {
        return code;
    }

    /** The length of one candle; a month's is its longest. */
    public Duration length() {
        return length;
    }

    /** The stored series of this timeframe (BR-08), or empty when it is read from the exchange. */
    public Optional<MarketInterval> stored() {
        return Optional.ofNullable(stored);
    }

    /** Whether the market has this timeframe: the exchange's Futures chart has no 1s. */
    public boolean availableOn(MarketType market) {
        return this != ONE_SECOND || market == MarketType.SPOT;
    }

    /** The timeframe the exchange spells this way, case sensitive, or empty. */
    public static Optional<ChartTimeframe> fromCode(String code) {
        return Arrays.stream(values()).filter(tf -> tf.code.equals(code)).findFirst();
    }
}

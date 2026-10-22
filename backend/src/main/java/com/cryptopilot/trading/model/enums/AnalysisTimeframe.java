package com.cryptopilot.trading.model.enums;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.util.Optional;

/**
 * The timeframe a plan was analysed on, one of the BR-08 timeframes. Its setup score is read on this timeframe. Each
 * constant carries the code stored in {@code trading_plan.analysis_timeframe} and used by the API.
 *
 * <p>Rule: BR-08, BR-13, BR-70; D-112; TECHNICAL_DESIGN 7.4.
 */
public enum AnalysisTimeframe {

    /** Fifteen minutes. */
    FIFTEEN_MINUTES("15m"),

    /** One hour. */
    ONE_HOUR("1h"),

    /** Four hours. */
    FOUR_HOURS("4h"),

    /** One day. */
    ONE_DAY("1d");

    private final String code;

    AnalysisTimeframe(String code) {
        this.code = code;
    }

    /** The timeframe as stored and as the API spells it, e.g. {@code 15m}. */
    public String code() {
        return code;
    }

    /** The timeframe spelled this way, or empty for anything outside BR-08. */
    public static Optional<AnalysisTimeframe> fromCode(String code) {
        for (AnalysisTimeframe timeframe : values()) {
            if (timeframe.code.equals(code)) {
                return Optional.of(timeframe);
            }
        }
        return Optional.empty();
    }

    /**
     * The default timeframe of a plan: the primary timeframe of the Trader's style preset, 1h when no preset is
     * selected (the DAY_TRADING fallback).
     *
     * <p>Rule: BR-13, BR-70; D-112.
     *
     * @param style the preset the Trader's profile selects, or empty
     */
    public static AnalysisTimeframe defaultFor(Optional<SetupStyle> style) {
        return style.map(selected -> switch (selected) {
                    case SCALPING -> FIFTEEN_MINUTES;
                    case DAY_TRADING -> ONE_HOUR;
                    case SWING -> FOUR_HOURS;
                })
                .orElse(ONE_HOUR);
    }
}

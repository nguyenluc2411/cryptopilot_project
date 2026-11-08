package com.cryptopilot.market.model.enums;

/**
 * The side a candle's components were scored for: always LONG on Spot, the dominant side of the trend on Futures.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4.
 */
public enum ScoredSide {
    LONG(1),
    SHORT(-1);

    private final int sign;

    ScoredSide(int sign) {
        this.sign = sign;
    }

    /** +1 for LONG, -1 for SHORT. */
    public int sign() {
        return sign;
    }
}

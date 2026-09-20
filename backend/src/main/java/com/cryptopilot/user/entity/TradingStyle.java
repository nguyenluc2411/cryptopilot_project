package com.cryptopilot.user.entity;

/**
 * How long a trader usually holds a position. A profile preference, used to pre-fill a plan and to
 * group performance statistics; it constrains nothing.
 *
 * <p>The constants are the values of the {@code ck_user_profile_trading_style} check constraint,
 * spelled the same way. The column is nullable — a profile that has never said is simply
 * {@code null}, which is why there is no {@code UNKNOWN} constant here.
 */
public enum TradingStyle {

    /** Minutes. Many positions a day, on the shortest timeframes. */
    SCALPING,

    /** Hours. Opened and closed within one session. */
    DAY,

    /** Days to weeks. Held across sessions, through a swing of the trend. */
    SWING,

    /** Weeks to months. Held through the larger trend. */
    POSITION
}

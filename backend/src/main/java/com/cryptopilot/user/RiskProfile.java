package com.cryptopilot.user;

/**
 * How much capital a Trader puts into a setup and when a plan is warned about (BR-66). It never changes a setup score
 * (BR-13).
 *
 * <p>The constants are the values of {@code ck_user_profile_risk_profile}, spelled the same way, declared from least
 * to most risk; the questionnaire relies on that order.
 *
 * <p>Rule: BR-66, BR-29; SRS 3.2.5; D-53.
 */
public enum RiskProfile {

    /** The smallest risk per trade and leverage; the default until the Trader chooses (D-64). */
    CONSERVATIVE,

    /** The middle profile. */
    BALANCED,

    /** The highest values any profile may have; choosing it needs the confirmation of MSG48. */
    AGGRESSIVE;

    /**
     * The profile of a Trader who has not chosen one: the lowest risk, so a Trader is never sized above what they have
     * accepted (D-64). The column default of {@code user_profile.risk_profile} is the same value.
     */
    public static final RiskProfile DEFAULT = CONSERVATIVE;
}

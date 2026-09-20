package com.cryptopilot.auth.entity;

/**
 * What a stored token is for. The three kinds share one table because they share one shape — a
 * hash, an expiry and a single use — and differ only in what presenting one achieves.
 *
 * <p>The constants are the values of the {@code ck_user_token_type} check constraint, spelled the
 * same way.
 *
 * <p>Rule: BR-01, BR-04; TECHNICAL_DESIGN section 5.3.
 */
public enum TokenType {

    /** Sent by mail after registration; valid for 24 hours and usable once (BR-01). */
    EMAIL_VERIFICATION,

    /** Sent by mail on request; valid for 30 minutes and usable once (BR-04). */
    PASSWORD_RESET,

    /**
     * Exchanged for a new access token. Rotated on every use, so presenting one that has already
     * been used means the value was copied, and the whole family issued by that sign-in is revoked
     * (TECHNICAL_DESIGN 7.15).
     */
    REFRESH
}

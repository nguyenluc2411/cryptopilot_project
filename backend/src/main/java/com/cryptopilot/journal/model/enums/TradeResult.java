package com.cryptopilot.journal.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The result of a closed trade, decided by the sign of its net P/L.
 *
 * <p>Rule: SRS 3.6.1; UC-22; BR-42.
 */
@Getter
@RequiredArgsConstructor
public enum TradeResult {

    /** Net P/L above 0. */
    WIN(1),

    /** Net P/L below 0. */
    LOSS(-1),

    /** Net P/L of exactly 0. */
    BREAKEVEN(0);

    /** The SQL {@code sign} of the net P/L of a trade with this result. */
    private final int sign;
}

package com.cryptopilot.trading.model;

import java.util.Objects;

/**
 * One value of a plan the calculation cannot accept, reported under its field: MSG15 for a value out of range or
 * below a limit, MSG16 for a price order that does not fit the direction, MSG01 for a value a Spot plan fixes.
 *
 * <p>Rule: BR-21, BR-22, BR-23, BR-30; SRS 3.5.1.
 *
 * @param field the input field, e.g. {@code stopLoss}, or {@code quantity} / {@code notional} for a result below the
 *     pair's minimum
 * @param messageCode MSG01, MSG15 or MSG16
 * @param detail a sentence for developers and logs; the Trader sees the message of {@code messageCode}
 */
public record InputViolation(String field, String messageCode, String detail) {

    public InputViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(messageCode, "messageCode");
        Objects.requireNonNull(detail, "detail");
    }
}

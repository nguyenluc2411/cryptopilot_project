package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The net profit and loss of a trade and whether it is final. It is provisional while the funding of the trade is
 * incomplete: the amount then holds the funding known so far and changes when a missing settlement is stored.
 *
 * <p>Rule: BR-38; A-05; D-97 note of 2026-10-24.
 *
 * @param amount realized minus fees plus the funding known so far
 * @param provisional whether the funding behind the amount is incomplete
 */
public record NetPnl(BigDecimal amount, boolean provisional) {

    public NetPnl {
        Objects.requireNonNull(amount, "amount");
    }
}

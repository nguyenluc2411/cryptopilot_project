package com.cryptopilot.market.model.enums;

/**
 * Where the prices a market's screens show come from (D-158): the exchange, the display-only fallback while the
 * exchange's data is lost, or none when the fallback is unavailable too.
 */
public enum DisplaySource {
    BINANCE,
    OKX,
    NONE
}

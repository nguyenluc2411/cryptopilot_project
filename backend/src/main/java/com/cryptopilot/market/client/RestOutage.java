package com.cryptopilot.market.client;

import java.time.Instant;
import java.util.Objects;

/**
 * Why a Binance venue cannot be called now, and until when (D-159).
 *
 * @param kind {@code BANNED} for a 418, {@code CIRCUIT_OPEN} after a run of failures
 * @param until when a call may be tried again
 */
public record RestOutage(BinanceClientException.Kind kind, Instant until) {

    public RestOutage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(until, "until");
    }
}

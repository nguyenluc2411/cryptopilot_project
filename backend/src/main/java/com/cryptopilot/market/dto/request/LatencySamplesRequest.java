package com.cryptopilot.market.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the client measured (TR-36): how long the realtime messages took from the exchange to the screen, by kind, as
 * the client computes it from the message's exchange time and its own clock corrected by the server's.
 *
 * @param samples 1 to 100 samples
 */
public record LatencySamplesRequest(
        @NotEmpty(message = "MSG01") @Size(max = 100, message = "MSG01")
        List<@Valid @NotNull(message = "MSG01") Sample> samples) {

    /**
     * One sample.
     *
     * @param kind what was measured
     * @param millis how long it took, 0 to 60 000 ms
     */
    public record Sample(
            @NotNull(message = "MSG01") Kind kind,

            @NotNull(message = "MSG01") @Min(value = 0, message = "MSG15") @Max(value = 60_000, message = "MSG15")
            Long millis) {}

    /** What a sample measures: a market topic's message, or a paper order's push after a fill. */
    public enum Kind {
        TICKER,
        KLINE,
        TRADES,
        DEPTH,
        ORDER
    }
}

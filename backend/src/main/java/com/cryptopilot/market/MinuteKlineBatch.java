package com.cryptopilot.market;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One page of closed 1-minute candles fetched from the exchange, oldest first, or a refusal to fetch now.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; A-04.
 *
 * @param klines closed candles, oldest first; empty when none closed from the requested time on, or when refused
 * @param retryAt when the exchange may be asked again, when it refused (rate limit, ban, outage); otherwise
 *     {@code null}
 */
public record MinuteKlineBatch(List<MinuteKline> klines, Instant retryAt) {

    public MinuteKlineBatch {
        klines = List.copyOf(Objects.requireNonNull(klines, "klines"));
    }

    /** A page of candles. */
    public static MinuteKlineBatch of(List<MinuteKline> klines) {
        return new MinuteKlineBatch(klines, null);
    }

    /** A refusal: nothing fetched, ask again at {@code retryAt}. */
    public static MinuteKlineBatch refusedUntil(Instant retryAt) {
        return new MinuteKlineBatch(List.of(), Objects.requireNonNull(retryAt, "retryAt"));
    }

    /** Whether the exchange refused the request. */
    public boolean refused() {
        return retryAt != null;
    }
}

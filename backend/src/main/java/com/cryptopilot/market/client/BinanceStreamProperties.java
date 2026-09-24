package com.cryptopilot.market.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The market streams of NSF-03, under {@code cryptopilot.market.stream}.
 *
 * <p>Off by default and on in the {@code dev} and {@code prod} profiles, like NSF-01 and NSF-02, so a test
 * context never opens a connection to the exchange. The hosts are the documented ones (TECHNICAL_DESIGN
 * 7.1.1): USDⓈ-M futures klines and mark prices are served under the routed path {@code /market} only since
 * 2026-04-23 — the unrouted host still accepts the connection and then sends nothing, which is why the path
 * is part of the configured URL rather than something the code appends.
 *
 * <p>Rule: NSF-03; BR-09; TECHNICAL_DESIGN 7.1 and 7.1.1.
 *
 * @param enabled whether the streams are opened at all
 * @param spotUrl the Spot stream host, e.g. {@code wss://stream.binance.com:9443}
 * @param futuresUrl the futures stream host with its route, e.g. {@code wss://fstream.binance.com/market}
 * @param maxStreamsPerConnection streams per combined connection (7.1 step 1: 100; the exchange allows 1,024); at
 *     least the five of one pair, which are never split
 * @param connectTimeout how long an opening handshake may take
 * @param reconnect the back-off after a lost connection
 * @param renewAfter when a connection is replaced, before the exchange's 24-hour limit ends it
 * @param idleTimeout how long a connection may stay silent before it is presumed dead and replaced; every
 *     connection carries a once-a-second ticker or mark price, so silence means a broken connection
 * @param refreshInterval how often the enabled pairs are read again to add or drop streams
 * @param snapshotInterval how often the latest prices are written as periodic snapshots (NSF-03: every minute)
 * @param snapshotMaxAge the oldest price a snapshot may carry; an older one is not written
 * @param partitions how many ordered consumers store closed candles, each serving a share of the pairs
 * @param queueCapacity closed candles a partition may hold before one is refused
 */
@Validated
@ConfigurationProperties("cryptopilot.market.stream")
public record BinanceStreamProperties(
        @DefaultValue("false") boolean enabled,

        @NotNull @DefaultValue("wss://stream.binance.com:9443")
        URI spotUrl,

        @NotNull @DefaultValue("wss://fstream.binance.com/market")
        URI futuresUrl,

        @Min(5) @Max(1024) @DefaultValue("100") int maxStreamsPerConnection,
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @Valid @DefaultValue Reconnect reconnect,
        @NotNull @DefaultValue("23h") Duration renewAfter,
        @NotNull @DefaultValue("60s") Duration idleTimeout,
        @NotNull @DefaultValue("5m") Duration refreshInterval,
        @NotNull @DefaultValue("60s") Duration snapshotInterval,
        @NotNull @DefaultValue("2m") Duration snapshotMaxAge,
        @Min(1) @Max(64) @DefaultValue("4") int partitions,
        @Min(1) @DefaultValue("10000") int queueCapacity) {

    /**
     * The back-off of NSF-03: from 1 s, doubling, up to 60 s.
     *
     * @param initial the first wait
     * @param max the longest wait
     * @param jitterPercent the largest share of a wait taken off at random
     */
    public record Reconnect(
            @NotNull @DefaultValue("1s") Duration initial,
            @NotNull @DefaultValue("60s") Duration max,
            @Min(0) @Max(100) @DefaultValue("20") int jitterPercent) {}
}

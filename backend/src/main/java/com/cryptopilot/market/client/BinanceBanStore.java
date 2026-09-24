package com.cryptopilot.market.client;

import java.time.Instant;
import java.util.Optional;

/**
 * Where the client remembers an IP ban beyond the life of the process.
 *
 * <p>A port the client owns and something outside it implements, so the client stays free of the
 * database — the layer rules keep a {@code client} away from repositories — while a 418 still survives a
 * restart. Binance bans grow with every repeat, from two minutes to three days; a restarted application
 * that forgot the ban and called again would be the repeat that makes the next one longer.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.1 and 7.1.2.
 *
 * <p>Reference: Binance (2026). <i>Spot API documentation</i>, "LIMITS"
 * (https://developers.binance.com/docs/binance-spot-api-docs/rest-api/limits): a 418 is an IP auto-ban
 * for continuing after 429s, scaling from 2 minutes to 3 days for repeat offenders.
 */
public interface BinanceBanStore {

    /** When the last recorded ban of this venue ends, or empty if none was ever recorded. */
    Optional<Instant> bannedUntil(BinanceVenue venue);

    /** Records that this venue is banned until this instant, replacing whatever was recorded before. */
    void recordBan(BinanceVenue venue, Instant bannedUntil, String reason);
}

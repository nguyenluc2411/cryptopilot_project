package com.cryptopilot.market.client;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A ban store held in a map, for the tests that exercise the client without a database. The one that
 * matters in production, and its survival across a restart, is proved against PostgreSQL in
 * {@code BinanceBanPersistenceTest}.
 */
public final class InMemoryBinanceBans implements BinanceBanStore {

    private final Map<BinanceVenue, Instant> bans = new EnumMap<>(BinanceVenue.class);

    @Override
    public synchronized Optional<Instant> bannedUntil(BinanceVenue venue) {
        return Optional.ofNullable(bans.get(venue));
    }

    @Override
    public synchronized void recordBan(BinanceVenue venue, Instant bannedUntil, String reason) {
        bans.put(venue, bannedUntil);
    }
}

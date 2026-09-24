package com.cryptopilot.market.service;

import com.cryptopilot.market.client.BinanceBanStore;
import com.cryptopilot.market.client.BinanceVenue;
import com.cryptopilot.market.repository.BinanceBanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a Binance IP ban in the database, so that it outlives a restart.
 *
 * <p>Maps the client's venue onto the market type the schema uses everywhere ({@code SPOT},
 * {@code FUTURES}); the reason is cut to the column's 500 characters, because a ban must be recorded even
 * when its description is long.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.2.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PersistentBinanceBans implements BinanceBanStore {

    private static final int REASON_LENGTH = 500;

    private final BinanceBanRepository bans;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> bannedUntil(BinanceVenue venue) {
        return bans.bannedUntil(marketTypeOf(venue));
    }

    @Override
    @Transactional
    public void recordBan(BinanceVenue venue, Instant bannedUntil, String reason) {
        String cut = reason.length() > REASON_LENGTH ? reason.substring(0, REASON_LENGTH) : reason;
        bans.save(marketTypeOf(venue), bannedUntil, cut, clock.instant());
    }

    static String marketTypeOf(BinanceVenue venue) {
        return venue == BinanceVenue.SPOT ? "SPOT" : "FUTURES";
    }
}

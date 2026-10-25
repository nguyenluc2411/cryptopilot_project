package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Waiting plans for the matching tests. */
public final class TrackedEntries {

    /** The owner of every plan built here. */
    public static final UUID OWNER = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");

    private TrackedEntries() {}

    /**
     * A LIMIT entry whose take profit lies a thousand times away from its entry (LONG above, SHORT below), so no test
     * price invalidates it and only its entry acts.
     */
    public static TrackedEntry limit(
            UUID planId, MarketType market, UUID pairId, Direction direction, BigDecimal price, Instant activatedAt) {
        boolean isLong = direction == Direction.LONG;
        return of(
                planId,
                market,
                pairId,
                direction,
                EntryType.LIMIT,
                price,
                isLong ? price.movePointLeft(3) : price.movePointRight(3),
                isLong ? price.movePointRight(3) : price.movePointLeft(3),
                null,
                activatedAt);
    }

    /** Any waiting plan of {@link #OWNER}. */
    public static TrackedEntry of(
            UUID planId,
            MarketType market,
            UUID pairId,
            Direction direction,
            EntryType entryType,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal invalidationPrice,
            Instant activatedAt) {
        return new TrackedEntry(
                planId,
                OWNER,
                market,
                pairId,
                direction,
                entryType,
                entryPrice,
                stopLoss,
                takeProfit,
                invalidationPrice,
                activatedAt);
    }
}

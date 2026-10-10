package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import org.springframework.modulith.NamedInterface;

/**
 * A stream connection of a market has opened again after it was lost: candles may have closed while nobody
 * listened. NSF-02 brings the market's series up to date, so the missing candles are stored within minutes of
 * the reconnection rather than when the next candle of each timeframe closes — up to a day later for 1d.
 *
 * <p>Rule: NSF-03 (any detected gap triggers NSF-02); SRS 4.2 (missing candles backfilled within 10 minutes of
 * reconnection); TECHNICAL_DESIGN 7.1 step 6.
 *
 * <p>Exposed alone as the named interface {@code events}: the alert engine of the {@code watchlist} module forgets
 * previous prices that may be stale when it hears it (NSF-06).
 *
 * @param market the market whose connection came back
 */
@NamedInterface("events")
public record MarketStreamReconnected(MarketType market) {}

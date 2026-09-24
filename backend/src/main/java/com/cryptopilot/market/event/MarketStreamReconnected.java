package com.cryptopilot.market.event;

import com.cryptopilot.market.MarketType;

/**
 * A stream connection of a market has opened again after it was lost: candles may have closed while nobody
 * listened. NSF-02 brings the market's series up to date, so the missing candles are stored within minutes of
 * the reconnection rather than when the next candle of each timeframe closes — up to a day later for 1d.
 *
 * <p>Rule: NSF-03 (any detected gap triggers NSF-02); SRS 4.2 (missing candles backfilled within 10 minutes of
 * reconnection); TECHNICAL_DESIGN 7.1 step 6.
 *
 * @param market the market whose connection came back
 */
public record MarketStreamReconnected(MarketType market) {}

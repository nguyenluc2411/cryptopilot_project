package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;

/**
 * The display-only fallback prices of a market (D-159), pushed to {@code /topic/market.fallback.<MARKET>} on every
 * poll while the market's exchange data is lost.
 *
 * @param market the market
 * @param source {@code OKX} while fallback prices are shown; {@code NONE} while the market's data is lost and the
 *     fallback is unavailable too; {@code BINANCE} while the exchange's own prices are live, with no prices here
 * @param asOf when they were read; {@code null} when there are none
 * @param prices one per pair the fallback lists, by symbol; a pair it does not list has no reference price
 */
public record FallbackPricesResponse(
        MarketType market, DisplaySource source, Instant asOf, List<FallbackPriceResponse> prices) {}

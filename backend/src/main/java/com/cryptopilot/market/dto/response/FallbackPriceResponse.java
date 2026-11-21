package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One pair's display-only fallback price while its market's exchange data is lost (D-159): shown labelled with its
 * source, never used to fill, trigger, liquidate or charge anything.
 *
 * @param symbol the exchange symbol, e.g. {@code BTCUSDT}
 * @param lastPrice the fallback source's last price
 * @param changePercent24h its change over 24 hours, in percent; {@code null} when the source gives no opening
 * @param high24h its highest price of the last 24 hours, if given
 * @param low24h its lowest price of the last 24 hours, if given
 * @param volume24h its base volume of the last 24 hours, if given
 * @param asOf when the source produced it
 * @param diverged whether it is further from the exchange's last price than the configured divergence: the banner warns
 *     it may differ from the exchange markedly
 */
public record FallbackPriceResponse(
        String symbol,
        BigDecimal lastPrice,
        BigDecimal changePercent24h,
        BigDecimal high24h,
        BigDecimal low24h,
        BigDecimal volume24h,
        Instant asOf,
        boolean diverged) {}

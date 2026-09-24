package com.cryptopilot.market.client;

import java.math.BigDecimal;

/**
 * One tradable symbol of an exchange-information response, reduced to what the system uses: its
 * trading status and the filters a price and a quantity are rounded to.
 *
 * <p>The filters are the exchange's, read at every synchronisation (NSF-01) and never guessed: a
 * wrong tick or step size becomes a wrong position size (BR-23, BR-30). A filter the exchange did not
 * send is {@code null} rather than zero, so a caller cannot mistake "absent" for "no minimum".
 *
 * <p>Rule: BR-07, BR-09; NSF-01.
 *
 * @param symbol the exchange symbol, e.g. {@code BTCUSDT}
 * @param status the exchange's trading status, e.g. {@code TRADING}
 * @param baseAsset the asset bought and sold, e.g. {@code BTC}
 * @param quoteAsset the asset it is priced in, e.g. {@code USDT}
 * @param contractType the futures contract type, e.g. {@code PERPETUAL}, or {@code null} on Spot
 * @param tickSize the price increment ({@code PRICE_FILTER.tickSize}), or {@code null}
 * @param stepSize the quantity increment ({@code LOT_SIZE.stepSize}), or {@code null}
 * @param minQuantity the smallest quantity ({@code LOT_SIZE.minQty}), or {@code null}
 * @param minNotional the smallest order value (Spot {@code NOTIONAL.minNotional} or
 *     {@code MIN_NOTIONAL.minNotional}, futures {@code MIN_NOTIONAL.notional}), or {@code null}
 */
public record ExchangeSymbol(
        String symbol,
        String status,
        String baseAsset,
        String quoteAsset,
        String contractType,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minQuantity,
        BigDecimal minNotional) {

    /** The status a symbol has while it can be traded; any other status is flagged by NSF-01. */
    public static final String TRADING = "TRADING";

    /** Whether the exchange still trades this symbol (NSF-01: a pair that stops trading is flagged). */
    public boolean isTrading() {
        return TRADING.equals(status);
    }
}

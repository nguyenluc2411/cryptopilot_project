package com.cryptopilot.market.model;

import java.math.BigDecimal;

/**
 * The Futures market data the derivatives component reads; a {@code null} value means it is not known.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 *
 * @param fundingRate the current predicted funding rate, as a fraction (0.0001 = 0.01%)
 * @param priceChange the price change over the last hour
 * @param openInterestChange the open interest change over the last hour
 */
public record DerivativesInputs(BigDecimal fundingRate, BigDecimal priceChange, BigDecimal openInterestChange) {}

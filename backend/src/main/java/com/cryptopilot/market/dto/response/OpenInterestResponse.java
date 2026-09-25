package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One open interest reading at the source's instant.
 *
 * <p>Rule: UC-09; NSF-04; BR-10; D-45.
 *
 * @param time the instant of the reading
 * @param openInterest the open contracts, in base-asset units
 * @param openInterestValue their value, in the quote asset
 */
public record OpenInterestResponse(Instant time, BigDecimal openInterest, BigDecimal openInterestValue) {}

package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One open interest reading of NSF-04, stored at the source's 5-minute instant.
 *
 * <p>Rule: NSF-04; BR-10; D-45.
 *
 * @param time the instant of the reading
 * @param openInterest the open contracts, in base-asset units
 * @param openInterestValue their value, in the quote asset
 */
public record OpenInterestReading(Instant time, BigDecimal openInterest, BigDecimal openInterestValue) {}

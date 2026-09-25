package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One global long/short account ratio reading of NSF-04, stored at the source's 5-minute instant.
 *
 * <p>Rule: NSF-04; BR-10; D-45.
 *
 * @param time the instant of the reading
 * @param longShortRatio accounts long divided by accounts short
 * @param longAccountRatio the share of accounts that are long
 * @param shortAccountRatio the share of accounts that are short
 */
public record LongShortReading(
        Instant time, BigDecimal longShortRatio, BigDecimal longAccountRatio, BigDecimal shortAccountRatio) {}

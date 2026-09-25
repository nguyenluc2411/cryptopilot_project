package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One global long/short account ratio reading at the source's instant.
 *
 * <p>Rule: UC-09; NSF-04; BR-10; D-45.
 *
 * @param time the instant of the reading
 * @param longShortRatio accounts long divided by accounts short
 * @param longAccountRatio the share of accounts that are long
 * @param shortAccountRatio the share of accounts that are short
 */
public record LongShortRatioResponse(
        Instant time, BigDecimal longShortRatio, BigDecimal longAccountRatio, BigDecimal shortAccountRatio) {}

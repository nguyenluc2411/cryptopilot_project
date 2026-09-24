package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The open interest of one futures symbol now ({@code GET /fapi/v1/openInterest}).
 *
 * <p>Only the present value: BR-10 says history beyond 30 days is not available from the source, so the
 * system builds its own history from these readings every five minutes (NSF-04).
 *
 * <p>Rule: BR-09, BR-10; NSF-04.
 *
 * @param symbol the futures symbol
 * @param openInterest the open contracts, in base-asset units
 * @param time the instant the exchange reported it
 */
public record OpenInterest(String symbol, BigDecimal openInterest, Instant time) {}

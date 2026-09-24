package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The global long/short account ratio of one futures symbol for one period
 * ({@code GET /futures/data/globalLongShortAccountRatio}).
 *
 * <p>The exchange keeps only the latest 30 days (BR-10); older history exists only in the system's own
 * snapshots.
 *
 * <p>Rule: BR-09, BR-10; NSF-04.
 *
 * @param symbol the futures symbol
 * @param longShortRatio accounts long divided by accounts short
 * @param longAccount the share of accounts that are long
 * @param shortAccount the share of accounts that are short
 * @param timestamp the instant of the period
 */
public record LongShortRatio(
        String symbol, BigDecimal longShortRatio, BigDecimal longAccount, BigDecimal shortAccount, Instant timestamp) {}

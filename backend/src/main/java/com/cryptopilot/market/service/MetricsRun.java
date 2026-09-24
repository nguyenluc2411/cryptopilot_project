package com.cryptopilot.market.service;

import java.util.List;

/**
 * What one NSF-04 metrics collection wrote.
 *
 * <p>Rule: NSF-04.
 *
 * @param openInterestRows open interest readings written
 * @param longShortRows long/short account ratio readings written
 * @param requests history calls made
 * @param stoppedAtRequestLimit whether the run stopped at its request limit; the next run continues
 * @param defects the series ({@code SYMBOL metric}) the exchange rejected or answered unreadably, skipped this run
 */
public record MetricsRun(
        int openInterestRows, int longShortRows, int requests, boolean stoppedAtRequestLimit, List<String> defects) {}

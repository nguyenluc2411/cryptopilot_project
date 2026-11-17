package com.cryptopilot.market.client;

import java.time.Instant;

/**
 * The Crypto Fear &amp; Greed Index of one day.
 *
 * @param value 0 (extreme fear) to 100 (extreme greed)
 * @param classification the published label, e.g. {@code Extreme Fear}
 * @param asOf the day it stands for
 */
public record FearGreedReading(int value, String classification, Instant asOf) {}

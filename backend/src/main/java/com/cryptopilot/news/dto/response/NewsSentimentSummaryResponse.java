package com.cryptopilot.news.dto.response;

import java.time.Instant;

/**
 * The summary bar of SCR-28: how many articles of the last 24 hours carry each sentiment, for every plan (D-126).
 * Articles not summarized yet carry none and are not counted.
 *
 * <p>Rule: UC-34; SRS 3.8.1; D-126.
 *
 * @param since the start of the 24 hours counted
 * @param bullish articles labelled {@code BULLISH}
 * @param neutral articles labelled {@code NEUTRAL}
 * @param bearish articles labelled {@code BEARISH}
 */
public record NewsSentimentSummaryResponse(Instant since, long bullish, long neutral, long bearish) {}

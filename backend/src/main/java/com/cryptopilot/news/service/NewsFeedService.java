package com.cryptopilot.news.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.dto.response.NewsArticleResponse;
import com.cryptopilot.news.dto.response.NewsSentimentSummaryResponse;

/**
 * The news feed of SCR-28 (UC-34), the same for every Trader whatever the plan (D-126).
 *
 * <p>Rule: UC-34; SRS 3.8.1; D-126.
 */
public interface NewsFeedService {

    /**
     * One page of the feed, newest first.
     *
     * @param coin only articles tagged with this coin symbol, e.g. {@code BTC}, or {@code null}
     * @param sentiment {@code BULLISH}, {@code NEUTRAL} or {@code BEARISH}, in any case: only articles with this
     *     sentiment; or {@code null}
     * @param range {@code 24h}, {@code 7d} or {@code 30d}; 7 days when {@code null}
     * @param page from 1, or {@code null} for 1
     * @param pageSize 1 to 100, or {@code null} for 20
     * @throws com.cryptopilot.common.exception.FieldValidationException naming {@code range}, {@code page} or
     *     {@code pageSize} with MSG15, or {@code sentiment} with MSG15
     */
    PageResponse<NewsArticleResponse> feed(String coin, String sentiment, String range, Integer page, Integer pageSize);

    /** How many articles of the last 24 hours carry each sentiment. */
    NewsSentimentSummaryResponse sentimentSummary();
}

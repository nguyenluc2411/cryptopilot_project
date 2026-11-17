package com.cryptopilot.community.dto.response;

/**
 * How the community leans on one coin over the last 30 days, for the Sentiment column of the market list: the
 * authors' Bullish and Bearish stances plus the readers' votes on the posts tagged with it. Public, like the feed.
 *
 * <p>Rule: D-125.
 *
 * @param symbol the coin, e.g. {@code BTC}
 * @param bullish Bullish stances and votes
 * @param bearish Bearish stances and votes
 */
public record CoinSentimentResponse(String symbol, long bullish, long bearish) {}

package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.Sentiment;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How the community feels about a coin today (UTC+7), the way CoinMarketCap Community shows it: the votes of the day
 * and the labels of the posts of the last 24 hours. An opinion poll, not advice; the client shows the disclaimer
 * (BR-60, MSG42).
 *
 * <p>Rule: BR-60; CR-02 (percentages with 2 decimals); D-99.
 *
 * @param symbol the coin, e.g. {@code BTC}
 * @param day the day the votes count for, in UTC+7
 * @param bullishVotes today's bullish votes
 * @param bearishVotes today's bearish votes
 * @param bullishPercent the bullish share of today's votes, 2 decimals; {@code null} without a vote
 * @param myVote the caller's vote today, or {@code null} (always {@code null} for a Guest)
 * @param bullishPosts published posts labelled bullish in the last 24 hours
 * @param bearishPosts published posts labelled bearish in the last 24 hours
 */
public record CoinSentimentResponse(
        String symbol,
        LocalDate day,
        long bullishVotes,
        long bearishVotes,
        BigDecimal bullishPercent,
        Sentiment myVote,
        long bullishPosts,
        long bearishPosts) {}

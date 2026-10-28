package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.PostSentiment;

/**
 * The Bullish and Bearish votes of a post after the caller voted (D-125).
 *
 * @param myVote the caller's vote, or {@code null} after taking it back
 * @param bullishCount the readers who voted it Bullish
 * @param bearishCount the readers who voted it Bearish
 */
public record VoteResponse(PostSentiment myVote, long bullishCount, long bearishCount) {}

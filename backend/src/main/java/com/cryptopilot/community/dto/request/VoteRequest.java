package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.PostSentiment;
import jakarta.validation.constraints.NotNull;

/**
 * A reader's Bullish or Bearish vote on a post (D-99); a new vote replaces the old one.
 *
 * @param sentiment BULLISH or BEARISH
 */
public record VoteRequest(@NotNull(message = "MSG01") PostSentiment sentiment) {}

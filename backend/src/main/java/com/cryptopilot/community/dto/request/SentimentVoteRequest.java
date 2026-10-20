package com.cryptopilot.community.dto.request;

import com.cryptopilot.community.model.enums.Sentiment;
import jakarta.validation.constraints.NotNull;

/**
 * "How do you feel about this coin today?" (D-99).
 *
 * @param sentiment BULLISH or BEARISH
 */
public record SentimentVoteRequest(
        @NotNull(message = "MSG01") Sentiment sentiment) {}

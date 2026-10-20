package com.cryptopilot.community.service;

import com.cryptopilot.community.dto.response.CoinSentimentResponse;
import com.cryptopilot.community.model.enums.Sentiment;
import java.util.UUID;

/**
 * How the community feels about a coin (D-99, CoinMarketCap Community): one vote per Trader, coin and day, and the
 * labels of recent posts. Implemented by {@link com.cryptopilot.community.service.impl.SentimentServiceImpl}.
 *
 * <p>Rule: BR-60; D-48, D-99.
 */
public interface SentimentService {

    /** Today's sentiment of a coin; {@code caller} is {@code null} for a Guest. MSG41 for a coin not stored. */
    CoinSentimentResponse coin(UUID caller, String symbol);

    /** Records the Trader's vote of today, replacing an earlier one of today. MSG41 for a coin no listed pair trades. */
    CoinSentimentResponse vote(UUID trader, String symbol, Sentiment sentiment);
}

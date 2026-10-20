package com.cryptopilot.community.model.enums;

/**
 * Whether a Trader expects a coin to rise or fall, as a post's label or a coin's daily vote, the way CoinMarketCap
 * Community shows it (D-99). An opinion of the community, never advice: the screens that show it carry the disclaimer
 * (BR-60, MSG42).
 *
 * <p>Rule: BR-60; D-99.
 */
public enum Sentiment {
    BULLISH,
    BEARISH
}

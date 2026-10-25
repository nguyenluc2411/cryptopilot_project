package com.cryptopilot.news.dto.response;

/**
 * A coin tag of a news item (NSF-10).
 *
 * <p>Rule: UC-34; NSF-10.
 *
 * @param symbol the coin's symbol, e.g. {@code BTC}
 * @param primary whether it is the first coin the headline names
 */
public record NewsCoinResponse(String symbol, boolean primary) {}

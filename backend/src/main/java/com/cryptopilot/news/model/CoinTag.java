package com.cryptopilot.news.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A coin an article mentions, as stored in {@code news_article_coin}.
 *
 * @param coinId the coin
 * @param primary whether it is the first coin the title names (NSF-10)
 */
public record CoinTag(UUID coinId, boolean primary) {

    public CoinTag {
        Objects.requireNonNull(coinId, "coinId must not be null");
    }
}

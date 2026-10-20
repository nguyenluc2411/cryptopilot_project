package com.cryptopilot.community.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * What the community talks about most, as CoinMarketCap Community's trending topics (D-99): the hashtags and the
 * coins of the most published posts since an instant.
 *
 * <p>Rule: D-99.
 *
 * @param since the start of the window
 * @param hashtags the most used hashtags, most posts first
 * @param coins the most discussed coins, most posts first
 */
public record TrendingResponse(Instant since, List<Topic> hashtags, List<Topic> coins) {

    public TrendingResponse {
        hashtags = List.copyOf(hashtags);
        coins = List.copyOf(coins);
    }

    /**
     * One topic and how many published posts carry it in the window.
     *
     * @param name a hashtag without its {@code #}, or a coin symbol
     * @param posts the published posts that carry it
     */
    public record Topic(String name, long posts) {}
}

package com.cryptopilot.ai;

import java.time.Instant;
import java.util.List;

/**
 * The news one question carries (T-074): articles about the coin asked about, newest first with those whose headline
 * names the coin ahead, and articles about the market that name no coin of the system.
 *
 * @param coin the symbol of the base coin the coin articles are about, e.g. {@code BTC}, or {@code null} when the pair
 *     is unknown
 * @param checkedUpTo when the outlets were last read, so the model can say how fresh its news is; {@code null} when no
 *     outlet has been read yet
 * @param coinArticles the articles tagged with the coin
 * @param marketArticles the articles tagged with no coin
 */
public record AssistantNews(
        String coin, Instant checkedUpTo, List<AssistantArticle> coinArticles, List<AssistantArticle> marketArticles) {

    public static final AssistantNews NONE = new AssistantNews(null, null, List.of(), List.of());

    public AssistantNews {
        coinArticles = coinArticles == null ? List.of() : List.copyOf(coinArticles);
        marketArticles = marketArticles == null ? List.of() : List.copyOf(marketArticles);
    }

    /** Whether there is no article at all. */
    public boolean isEmpty() {
        return coinArticles.isEmpty() && marketArticles.isEmpty();
    }
}

package com.cryptopilot.ai.model;

import com.cryptopilot.ai.AssistantArticle;
import com.cryptopilot.ai.AssistantNews;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The news part of a question's context (T-074): the recent articles about the coin asked about and about the market,
 * numbered from 1 so the answer can cite them as {@code [1]}, {@code [2]}, and the time the outlets were last read, so
 * the model says how fresh they are. Articles carry nothing about the Trader (BR-51).
 *
 * <p>Rule: BR-51; T-074.
 *
 * @param coin the base coin the coin articles are about, or {@code null}
 * @param checkedUpTo when the outlets were last read, or {@code null} when never
 * @param articles the coin articles first, then the market ones, numbered in that order
 */
public record NewsContext(String coin, Instant checkedUpTo, List<NewsItem> articles) {

    /** What an article is about: the coin asked about, or the market in general. */
    public enum About {
        COIN,
        MARKET
    }

    /**
     * One article as the model reads it.
     *
     * @param ref the number the answer cites it by, from 1
     * @param about whether it is about the coin or the market
     * @param headline the headline, in Vietnamese when NSF-11 translated it
     * @param outlet the news source
     * @param publishedAt when it was published
     * @param summary a short summary, or {@code null}
     * @param sentiment the tone NSF-11 gave it, or {@code null}
     * @param url the original article
     */
    public record NewsItem(
            int ref,
            About about,
            String headline,
            String outlet,
            Instant publishedAt,
            String summary,
            String sentiment,
            String url) {}

    public NewsContext {
        articles = articles == null ? List.of() : List.copyOf(articles);
    }

    /** The news read from the source, numbered: the coin articles, then the market ones. */
    public static NewsContext of(AssistantNews news) {
        Objects.requireNonNull(news, "news");
        List<NewsItem> items = new ArrayList<>();
        for (AssistantArticle article : news.coinArticles()) {
            items.add(item(items.size() + 1, About.COIN, article));
        }
        for (AssistantArticle article : news.marketArticles()) {
            items.add(item(items.size() + 1, About.MARKET, article));
        }
        return new NewsContext(news.coin(), news.checkedUpTo(), items);
    }

    private static NewsItem item(int ref, About about, AssistantArticle article) {
        return new NewsItem(
                ref,
                about,
                article.headline(),
                article.outlet(),
                article.publishedAt(),
                article.summary(),
                article.sentiment(),
                article.url());
    }
}

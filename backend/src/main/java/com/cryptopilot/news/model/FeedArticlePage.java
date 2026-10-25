package com.cryptopilot.news.model;

import java.util.List;

/**
 * One page of the news feed in feed order, and how many articles match the request across all pages.
 *
 * @param articles the page
 * @param total every matching article
 */
public record FeedArticlePage(List<FeedArticle> articles, long total) {

    public FeedArticlePage {
        articles = List.copyOf(articles);
    }
}

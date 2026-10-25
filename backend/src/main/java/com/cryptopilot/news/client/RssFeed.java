package com.cryptopilot.news.client;

import java.util.List;

/**
 * A feed as it was read: its title and its items in the order the feed lists them.
 *
 * @param title the feed's own title, or {@code null}
 * @param items the items, possibly none
 */
public record RssFeed(String title, List<RssItem> items) {

    public RssFeed {
        items = List.copyOf(items);
    }
}

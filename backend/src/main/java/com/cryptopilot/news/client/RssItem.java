package com.cryptopilot.news.client;

import java.time.Instant;
import java.util.List;

/**
 * One item of a feed, as the outlet wrote it: nothing is normalized or cleaned here.
 *
 * @param title the headline, or {@code null}
 * @param link the address of the article, or {@code null}
 * @param publishedAt when it was published, or when it was last updated if the feed says only that, or {@code null}
 * @param description the summary or the content the feed carries, as HTML, or {@code null}
 * @param categories the names and the domains of the item's categories, in the feed's order
 * @param imageUrl the address of the item's image, from an image enclosure or a Media RSS element, or {@code null}
 */
public record RssItem(
        String title, String link, Instant publishedAt, String description, List<String> categories, String imageUrl) {

    public RssItem {
        categories = List.copyOf(categories);
    }

    /** An item without an image. */
    public RssItem(String title, String link, Instant publishedAt, String description, List<String> categories) {
        this(title, link, publishedAt, description, categories, null);
    }
}

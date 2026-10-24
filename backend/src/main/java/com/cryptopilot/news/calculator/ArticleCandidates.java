package com.cryptopilot.news.calculator;

import com.cryptopilot.news.model.ArticleCandidate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;

/**
 * Turns a feed item into what an article stores (BR-48): the headline as text, the address and the hashes duplicates
 * are found by (TECHNICAL_DESIGN 7.13), the publish time, the excerpt and the coins it mentions (NSF-10). An item
 * without a headline, or without an address that is a web address, cannot be an article.
 *
 * <p>A publish time missing from the feed is the time the item was collected, and one in the future is brought back
 * to it: an outlet's clock or a time zone written wrongly must not keep a story at the top of the feed.
 *
 * <p>Rule: BR-48; NSF-10; TECHNICAL_DESIGN 7.13.
 */
public final class ArticleCandidates {

    /** The length of {@code news_article.title}. */
    static final int MAX_TITLE_LENGTH = 500;

    /** The length of {@code news_article.original_url}. */
    static final int MAX_URL_LENGTH = 2048;

    private ArticleCandidates() {}

    /**
     * The article this item would be, or empty when it has no headline or no usable address.
     *
     * @param publishedAt when the feed says it was published, or {@code null}
     * @param description the feed's description of it, as HTML, or {@code null}
     * @param collectedAt when the crawl read it
     */
    public static Optional<ArticleCandidate> from(
            String title,
            String link,
            Instant publishedAt,
            String description,
            Instant collectedAt,
            CoinTagger tagger) {
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
        Objects.requireNonNull(tagger, "tagger must not be null");
        String headline = title == null ? "" : Jsoup.parse(title).text().strip();
        if (headline.isEmpty() || link == null) {
            return Optional.empty();
        }
        Optional<String> normalized = UrlNormalizer.normalize(link);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String original = link.strip();
        if (original.length() > MAX_URL_LENGTH) {
            original = normalized.get();
            if (original.length() > MAX_URL_LENGTH) {
                return Optional.empty();
            }
        }
        if (headline.length() > MAX_TITLE_LENGTH) {
            headline = headline.substring(0, MAX_TITLE_LENGTH).stripTrailing();
        }
        String excerpt = Excerpt.of(description);
        Instant published = publishedAt == null || publishedAt.isAfter(collectedAt) ? collectedAt : publishedAt;
        return Optional.of(new ArticleCandidate(
                headline,
                original,
                ContentHash.sha256(normalized.get()),
                ContentHash.sha256(TitleNormalizer.normalize(headline)),
                excerpt,
                published,
                tagger.tag(headline, excerpt)));
    }

    /**
     * Whether an item with these categories passes a source's category filter: any item when there is no filter, else
     * an item that carries one of the filter's comma-separated values as a category name or domain, case ignored.
     */
    public static boolean passes(String categoryFilter, Collection<String> categories) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return true;
        }
        Set<String> wanted = Arrays.stream(categoryFilter.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
        return wanted.isEmpty()
                || categories.stream()
                        .map(category -> category.strip().toLowerCase(Locale.ROOT))
                        .anyMatch(wanted::contains);
    }
}

package com.cryptopilot.news.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A feed item made ready to be stored as an article: only what BR-48 allows — the title, the original address, the
 * publish time and an excerpt — with the two hashes duplicates are found by and the coins it mentions.
 *
 * @param title the headline as text, at most 500 characters
 * @param originalUrl the address as the outlet wrote it, at most 2048 characters
 * @param urlHash the SHA-256 of the normalized address, hex
 * @param titleHash the SHA-256 of the normalized title, hex
 * @param excerpt the text of the feed's description, at most 500 characters, or {@code null}
 * @param publishedAt when the outlet published it, never later than when it was collected
 * @param coins the coins it mentions, the primary one first
 * @param imageUrl the address of the feed's image for it (D-129), or {@code null}
 */
public record ArticleCandidate(
        String title,
        String originalUrl,
        String urlHash,
        String titleHash,
        String excerpt,
        Instant publishedAt,
        List<CoinTag> coins,
        String imageUrl) {

    public ArticleCandidate {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(originalUrl, "originalUrl must not be null");
        Objects.requireNonNull(urlHash, "urlHash must not be null");
        Objects.requireNonNull(titleHash, "titleHash must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        coins = List.copyOf(coins);
    }
}

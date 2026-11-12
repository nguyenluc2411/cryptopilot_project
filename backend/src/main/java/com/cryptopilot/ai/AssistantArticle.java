package com.cryptopilot.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * One article as the AI Assistant reads it: the headline (in Vietnamese when NSF-11 translated it), the outlet, when
 * it was published, a short summary (the AI summary, else the feed's excerpt), the tone NSF-11 gave it and the link to
 * the original, which the answer cites so the Trader can read it.
 *
 * @param headline the headline
 * @param outlet the name of the news source
 * @param publishedAt when the outlet published it
 * @param summary a short summary, or {@code null}
 * @param sentiment {@code BULLISH}, {@code NEUTRAL} or {@code BEARISH}, or {@code null} before NSF-11 labels it
 * @param url the original article
 */
public record AssistantArticle(
        String headline, String outlet, Instant publishedAt, String summary, String sentiment, String url) {

    public AssistantArticle {
        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(outlet, "outlet");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(url, "url");
    }
}

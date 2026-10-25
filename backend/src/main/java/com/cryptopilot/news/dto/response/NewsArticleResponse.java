package com.cryptopilot.news.dto.response;

import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One item of the news feed on SCR-28, the same for every plan (D-126). The text is the AI's once NSF-11 has run
 * (D-127), the collected one until then. Selecting it opens the article's page in the app (D-129).
 *
 * <p>Rule: UC-34; SRS 3.8.1; NSF-11; D-126, D-127, D-129.
 *
 * @param articleId the article
 * @param title the translated headline, or the collected one while the article is not summarized
 * @param originalTitle the headline as the outlet wrote it
 * @param source the outlet's name
 * @param originalUrl the article at the outlet, linked at the end of its page in the app
 * @param publishedAt when the outlet published it
 * @param summary the AI summary, or the feed's excerpt while there is none; {@code null} when there is neither
 * @param aiSummarized whether {@code summary} is the AI's; the title is translated too, except for articles summarized
 *     before the headline was (D-127)
 * @param sentiment the article's tone, or {@code null} while it is not summarized
 * @param coins the coins it is tagged with, the primary one first
 * @param imageUrl the address of the outlet's cover image, shown from the outlet, or {@code null} for a placeholder
 */
public record NewsArticleResponse(
        UUID articleId,
        String title,
        String originalTitle,
        String source,
        String originalUrl,
        Instant publishedAt,
        String summary,
        boolean aiSummarized,
        SentimentLabel sentiment,
        List<NewsCoinResponse> coins,
        String imageUrl) {

    public NewsArticleResponse {
        coins = List.copyOf(coins);
    }
}

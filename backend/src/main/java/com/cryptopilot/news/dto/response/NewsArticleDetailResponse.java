package com.cryptopilot.news.dto.response;

import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One article read on its own page in the app (D-129): the feed item with the article written again in the app's
 * words, and the outlet and the address of the original, which the page names at its end. The full original text is
 * never stored (BR-48).
 *
 * <p>Rule: UC-34; SRS 3.8.1; BR-48; D-126, D-127, D-129.
 *
 * @param articleId the article
 * @param title the translated headline, or the collected one while the article is not summarized
 * @param originalTitle the headline as the outlet wrote it
 * @param source the outlet's name
 * @param originalUrl the article at the outlet
 * @param publishedAt when the outlet published it
 * @param summary the AI summary, or the feed's excerpt while there is none; {@code null} when there is neither
 * @param body the article written again in paragraphs separated by a blank line, or {@code null} while there is none,
 *     when the page shows the summary
 * @param aiSummarized whether {@code summary} and {@code body} are the AI's
 * @param sentiment the article's tone, or {@code null} while it is not summarized
 * @param coins the coins it is tagged with, the primary one first
 * @param imageUrl the address of the outlet's cover image, shown from the outlet, or {@code null} for a placeholder
 */
public record NewsArticleDetailResponse(
        UUID articleId,
        String title,
        String originalTitle,
        String source,
        String originalUrl,
        Instant publishedAt,
        String summary,
        String body,
        boolean aiSummarized,
        SentimentLabel sentiment,
        List<NewsCoinResponse> coins,
        String imageUrl) {

    public NewsArticleDetailResponse {
        coins = List.copyOf(coins);
    }
}

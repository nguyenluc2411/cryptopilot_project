package com.cryptopilot.academy.dto.response;

import com.cryptopilot.academy.model.enums.ArticleLevel;
import java.time.Instant;
import java.util.List;

/**
 * A lesson page: the lesson, where it leads in the application, and where to read on.
 *
 * <p>The previous and the next lesson follow the beginner path when the lesson is on it, and its topic otherwise.
 *
 * @param slug the key of the lesson page
 * @param title the heading
 * @param summary the short summary shown above the body
 * @param body the text, in Markdown
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time
 * @param topicSlug the slug of the lesson's topic
 * @param topicName the name of the lesson's topic
 * @param pathOrder the lesson's place on the beginner path, or {@code null} when it is not on it
 * @param appLink the screen of the application the lesson leads to, such as {@code /paper}, or {@code null}
 * @param publishedAt when the lesson was published
 * @param updatedAt when the lesson was last changed
 * @param previous the lesson before this one, or {@code null}
 * @param next the lesson after this one, or {@code null}
 * @param related up to three other lessons of the same topic
 */
public record ArticleDetailResponse(
        String slug,
        String title,
        String summary,
        String body,
        ArticleLevel level,
        int readingMinutes,
        String topicSlug,
        String topicName,
        Integer pathOrder,
        String appLink,
        Instant publishedAt,
        Instant updatedAt,
        ArticleLinkResponse previous,
        ArticleLinkResponse next,
        List<ArticleSummaryResponse> related) {

    public ArticleDetailResponse {
        related = List.copyOf(related);
    }
}

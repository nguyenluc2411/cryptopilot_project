package com.cryptopilot.academy.dto.response;

import com.cryptopilot.academy.model.enums.ArticleLevel;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A lesson as the editor of the administration console opens it.
 *
 * @param id the key of the lesson
 * @param topic the slug of its topic
 * @param slug the key of the lesson page
 * @param title the heading
 * @param summary the short summary
 * @param body the text, in Markdown
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time
 * @param sortOrder the place inside the topic
 * @param pathOrder the place on the beginner path, or {@code null}
 * @param appLink the screen of the application the lesson leads to, or {@code null}
 * @param status where the lesson stands
 * @param publishedAt the first publication, or {@code null}
 * @param createdAt when the lesson was created
 * @param updatedAt the last change
 */
public record AdminArticleResponse(
        UUID id,
        String topic,
        String slug,
        String title,
        String summary,
        String body,
        ArticleLevel level,
        int readingMinutes,
        int sortOrder,
        Integer pathOrder,
        String appLink,
        ArticleStatus status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {}

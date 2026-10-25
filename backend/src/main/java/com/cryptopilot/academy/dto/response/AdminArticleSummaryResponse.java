package com.cryptopilot.academy.dto.response;

import com.cryptopilot.academy.model.enums.ArticleLevel;
import com.cryptopilot.academy.model.enums.ArticleStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A lesson as the administration console lists it, whatever its status.
 *
 * @param id the key of the lesson
 * @param slug the key of the lesson page
 * @param title the heading
 * @param topicSlug the slug of its topic
 * @param topicName the name of its topic
 * @param level how much the reader should already know
 * @param status where the lesson stands
 * @param sortOrder the place inside the topic
 * @param pathOrder the place on the beginner path, or {@code null}
 * @param publishedAt the first publication, or {@code null}
 * @param updatedAt the last change
 */
public record AdminArticleSummaryResponse(
        UUID id,
        String slug,
        String title,
        String topicSlug,
        String topicName,
        ArticleLevel level,
        ArticleStatus status,
        int sortOrder,
        Integer pathOrder,
        Instant publishedAt,
        Instant updatedAt) {}

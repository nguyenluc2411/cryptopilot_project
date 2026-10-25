package com.cryptopilot.academy.dto.response;

import com.cryptopilot.academy.model.enums.ArticleLevel;

/**
 * A lesson as a card shows it.
 *
 * @param slug the key of the lesson page
 * @param title the heading
 * @param summary the short summary
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time
 * @param topicSlug the slug of the lesson's topic
 * @param topicName the name of the lesson's topic
 * @param pathOrder the lesson's place on the beginner path, or {@code null} when it is not on it
 */
public record ArticleSummaryResponse(
        String slug,
        String title,
        String summary,
        ArticleLevel level,
        int readingMinutes,
        String topicSlug,
        String topicName,
        Integer pathOrder) {}

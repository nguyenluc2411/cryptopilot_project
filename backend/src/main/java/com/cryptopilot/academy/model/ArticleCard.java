package com.cryptopilot.academy.model;

import com.cryptopilot.academy.model.enums.ArticleLevel;

/**
 * A published lesson as a list shows it, without its body.
 *
 * @param slug the key the client addresses the lesson by
 * @param title the heading
 * @param summary the short summary shown on the card and above the body
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time
 * @param topicSlug the slug of the lesson's topic
 * @param topicName the name of the lesson's topic
 * @param pathOrder the lesson's place on the beginner path, or {@code null} when it is not on it
 * @param coverImageUrl the address of the cover photo, or {@code null}
 */
public record ArticleCard(
        String slug,
        String title,
        String summary,
        ArticleLevel level,
        int readingMinutes,
        String topicSlug,
        String topicName,
        Integer pathOrder,
        String coverImageUrl) {}

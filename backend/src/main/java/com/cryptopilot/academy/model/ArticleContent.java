package com.cryptopilot.academy.model;

import com.cryptopilot.academy.model.enums.ArticleLevel;
import java.util.UUID;

/**
 * What an administrator writes on a lesson; the status and its instants are changed by publishing and hiding.
 *
 * @param topicId the topic the lesson belongs to
 * @param slug the key of the lesson page
 * @param title the heading
 * @param summary the short summary
 * @param body the text, in Markdown
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time, 1 to 120
 * @param sortOrder the place inside the topic
 * @param pathOrder the place on the beginner path, or {@code null} when it is not on it
 * @param appLink the screen of the application the lesson leads to, or {@code null}
 * @param coverImageUrl the address of the cover photo, or {@code null}
 * @param coverCredit who took the cover photo and where it comes from, or {@code null}
 */
public record ArticleContent(
        UUID topicId,
        String slug,
        String title,
        String summary,
        String body,
        ArticleLevel level,
        int readingMinutes,
        int sortOrder,
        Integer pathOrder,
        String appLink,
        String coverImageUrl,
        String coverCredit) {}

package com.cryptopilot.academy.model;

import java.time.Instant;

/**
 * A published lesson with its body.
 *
 * @param card the fields a list shows
 * @param body the text, in Markdown
 * @param appLink the screen of the application the lesson leads to, such as {@code /paper}, or {@code null}
 * @param publishedAt when the lesson was published
 * @param updatedAt when the lesson was last changed
 * @param coverCredit who took the cover photo and where it comes from, or {@code null}
 */
public record ArticleText(
        ArticleCard card, String body, String appLink, Instant publishedAt, Instant updatedAt, String coverCredit) {}

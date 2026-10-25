package com.cryptopilot.academy.dto.request;

import com.cryptopilot.academy.model.enums.ArticleLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The lesson editor of the administration console: a new lesson, or the new content of one. Leading and trailing
 * white space is removed before the lengths are checked, and a blank app link is none.
 *
 * <p>Rule: T-114.
 *
 * @param topic the slug of the lesson's topic
 * @param slug the key of the lesson page: lower-case letters and digits in words joined by single hyphens
 * @param title 1 to 200 characters
 * @param summary 1 to 500 characters
 * @param body the text in Markdown, 1 to 100 000 characters
 * @param level how much the reader should already know
 * @param readingMinutes the estimated reading time, 1 to 120
 * @param sortOrder the place inside the topic, from 0
 * @param pathOrder the place on the beginner path from 1, or {@code null} when it is not on it
 * @param appLink a screen of the application such as {@code /trade/spot}, or {@code null}
 */
public record ArticleRequest(
        @NotBlank(message = "MSG01") String topic,

        @NotBlank(message = "MSG01") @Size(max = 120, message = "MSG01") @Pattern(regexp = SLUG, message = "MSG01")
        String slug,

        @NotBlank(message = "MSG01") @Size(max = 200, message = "MSG01")
        String title,

        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG01")
        String summary,

        @NotBlank(message = "MSG01") @Size(max = 100_000, message = "MSG01")
        String body,

        @NotNull(message = "MSG01") ArticleLevel level,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15") @Max(value = 120, message = "MSG15")
        Integer readingMinutes,

        @NotNull(message = "MSG01") @Min(value = 0, message = "MSG15")
        Integer sortOrder,

        @Min(value = 1, message = "MSG15") Integer pathOrder,

        @Size(max = 200, message = "MSG01") @Pattern(regexp = APP_LINK, message = "MSG01")
        String appLink) {

    /** Lower-case words of letters and digits joined by single hyphens. */
    public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** A path of the application: one leading slash, never two. */
    public static final String APP_LINK = "^/([^/].*)?$";

    public ArticleRequest {
        topic = strip(topic);
        slug = strip(slug);
        title = strip(title);
        summary = strip(summary);
        body = strip(body);
        appLink = appLink == null || appLink.isBlank() ? null : appLink.strip();
    }

    private static String strip(String text) {
        return text == null ? null : text.strip();
    }
}

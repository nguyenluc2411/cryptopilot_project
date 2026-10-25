package com.cryptopilot.academy.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The topic editor of the administration console: a new topic, or the new content of one. Leading and trailing white
 * space is removed before the lengths are checked.
 *
 * <p>Rule: T-114.
 *
 * @param slug the key of the topic, in the form of {@link ArticleRequest#SLUG}, up to 80 characters
 * @param name 1 to 120 characters
 * @param description 1 to 500 characters
 * @param sortOrder the place on the Academy home, from 0
 * @param version the version the administrator edited, required to change a topic and ignored for a new one
 */
public record TopicRequest(
        @NotBlank(message = "MSG01")
        @Size(max = 80, message = "MSG01")
        @Pattern(regexp = ArticleRequest.SLUG, message = "MSG01")
        String slug,

        @NotBlank(message = "MSG01") @Size(max = 120, message = "MSG01")
        String name,

        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG01")
        String description,

        @NotNull(message = "MSG01") @Min(value = 0, message = "MSG15")
        Integer sortOrder,

        @Min(value = 0, message = "MSG15") Long version) {

    public TopicRequest {
        slug = slug == null ? null : slug.strip();
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
    }
}

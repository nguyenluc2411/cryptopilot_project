package com.cryptopilot.academy.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The glossary editor of the administration console: a new term, or the new content of one. Leading and trailing
 * white space is removed before the lengths are checked, and a blank lesson is none.
 *
 * <p>Rule: T-114.
 *
 * @param slug the key of the term, in the form of {@link ArticleRequest#SLUG}
 * @param term 1 to 120 characters
 * @param definition 1 to 1 000 characters
 * @param articleSlug the slug of the lesson that explains the term, or {@code null}
 * @param version the version the administrator edited, required to change a term and ignored for a new one
 */
public record TermRequest(
        @NotBlank(message = "MSG01")
        @Size(max = 120, message = "MSG01")
        @Pattern(regexp = ArticleRequest.SLUG, message = "MSG01")
        String slug,

        @NotBlank(message = "MSG01") @Size(max = 120, message = "MSG01")
        String term,

        @NotBlank(message = "MSG01") @Size(max = 1_000, message = "MSG01")
        String definition,

        String articleSlug,

        @Min(value = 0, message = "MSG15") Long version) {

    public TermRequest {
        slug = slug == null ? null : slug.strip();
        term = term == null ? null : term.strip();
        definition = definition == null ? null : definition.strip();
        articleSlug = articleSlug == null || articleSlug.isBlank() ? null : articleSlug.strip();
    }
}

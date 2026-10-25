package com.cryptopilot.academy.model;

/**
 * A term of the glossary.
 *
 * @param slug the key of the term
 * @param term the term as it is written
 * @param definition what it means, in a sentence or two
 * @param articleSlug the published lesson that explains the term at length, or {@code null}
 * @param articleTitle the title of that lesson, or {@code null}
 */
public record GlossaryEntry(String slug, String term, String definition, String articleSlug, String articleTitle) {}

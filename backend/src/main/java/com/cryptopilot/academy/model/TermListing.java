package com.cryptopilot.academy.model;

import java.util.UUID;

/**
 * A term as the administration console lists it.
 *
 * @param id the key of the term
 * @param slug the slug of the term
 * @param term the term as it is written
 * @param definition what it means
 * @param articleSlug the lesson it points at, whatever that lesson's status, or {@code null}
 */
public record TermListing(UUID id, String slug, String term, String definition, String articleSlug) {}

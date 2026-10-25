package com.cryptopilot.academy.dto.response;

import java.util.UUID;

/**
 * A term as the administration console lists and edits it.
 *
 * @param id the key of the term
 * @param slug the slug of the term
 * @param term the term as it is written
 * @param definition what it means
 * @param articleSlug the lesson it points at, whatever that lesson's status, or {@code null}
 * @param version the version to send back with a change
 */
public record AdminTermResponse(
        UUID id, String slug, String term, String definition, String articleSlug, long version) {}

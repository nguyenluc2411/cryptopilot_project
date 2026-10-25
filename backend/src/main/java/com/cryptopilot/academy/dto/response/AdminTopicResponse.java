package com.cryptopilot.academy.dto.response;

import java.util.UUID;

/**
 * A topic as the administration console lists and edits it.
 *
 * @param id the key of the topic
 * @param slug the slug of the topic
 * @param name the heading
 * @param description one or two sentences about the topic
 * @param sortOrder the place on the Academy home
 * @param articles how many lessons the topic holds, whatever their status
 * @param version the version to send back with a change
 */
public record AdminTopicResponse(
        UUID id, String slug, String name, String description, int sortOrder, long articles, long version) {}

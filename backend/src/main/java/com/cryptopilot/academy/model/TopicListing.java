package com.cryptopilot.academy.model;

import java.util.UUID;

/**
 * A topic as the administration console lists it.
 *
 * @param id the key of the topic
 * @param slug the slug of the topic
 * @param name the heading
 * @param description one or two sentences about the topic
 * @param sortOrder the place on the Academy home
 * @param articles how many lessons the topic holds, whatever their status
 * @param version the row's version
 */
public record TopicListing(
        UUID id, String slug, String name, String description, int sortOrder, long articles, long version) {}

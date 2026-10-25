package com.cryptopilot.academy.dto.response;

/**
 * A topic of the Academy home page.
 *
 * @param slug the key to filter the lessons by
 * @param name the heading
 * @param description one or two sentences about the topic
 * @param articles how many published lessons the topic holds
 */
public record TopicResponse(String slug, String name, String description, long articles) {}

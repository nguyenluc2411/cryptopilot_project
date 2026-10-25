package com.cryptopilot.academy.dto.response;

/**
 * A lesson named from another lesson's page, such as the previous and the next one.
 *
 * @param slug the key of the lesson page
 * @param title the heading
 */
public record ArticleLinkResponse(String slug, String title) {}

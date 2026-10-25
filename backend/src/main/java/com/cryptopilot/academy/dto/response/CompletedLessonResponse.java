package com.cryptopilot.academy.dto.response;

import java.time.Instant;

/**
 * A lesson the caller marked as learned.
 *
 * @param slug the key of the lesson
 * @param completedAt when it was marked
 */
public record CompletedLessonResponse(String slug, Instant completedAt) {}

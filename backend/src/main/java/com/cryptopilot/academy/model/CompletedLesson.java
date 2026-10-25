package com.cryptopilot.academy.model;

import java.time.Instant;

/**
 * A published lesson a Trader marked as learned.
 *
 * @param slug the key of the lesson
 * @param onPath whether the lesson is on the beginner path
 * @param completedAt when the Trader marked it
 */
public record CompletedLesson(String slug, boolean onPath, Instant completedAt) {}

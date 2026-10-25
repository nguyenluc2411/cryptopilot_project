package com.cryptopilot.academy.model;

/**
 * How many lessons are published.
 *
 * @param published every published lesson
 * @param onPath the published lessons on the beginner path
 */
public record LessonTotals(long published, long onPath) {}

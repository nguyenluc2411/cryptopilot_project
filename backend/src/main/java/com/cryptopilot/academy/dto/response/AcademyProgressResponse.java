package com.cryptopilot.academy.dto.response;

import java.util.List;

/**
 * How far the caller is in the Academy.
 *
 * @param completed the published lessons the caller marked as learned, oldest mark first
 * @param publishedTotal how many lessons are published
 * @param pathCompleted how many lessons of the beginner path the caller marked
 * @param pathTotal how many lessons the beginner path holds
 */
public record AcademyProgressResponse(
        List<CompletedLessonResponse> completed, long publishedTotal, long pathCompleted, long pathTotal) {

    public AcademyProgressResponse {
        completed = List.copyOf(completed);
    }
}

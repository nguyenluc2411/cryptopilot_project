package com.cryptopilot.academy.service;

import com.cryptopilot.academy.dto.response.AcademyProgressResponse;
import com.cryptopilot.academy.dto.response.CompletedLessonResponse;
import java.util.UUID;

/**
 * A Trader's progress in the Academy: the published lessons they marked as learned. A lesson that is not published
 * answers MSG41. Implemented by {@link com.cryptopilot.academy.service.impl.AcademyProgressServiceImpl}.
 *
 * <p>Rule: D-48; T-114.
 */
public interface AcademyProgressService {

    /** The caller's progress. */
    AcademyProgressResponse progress(UUID userId);

    /** Marks a published lesson as learned; marking it again keeps the first instant. */
    CompletedLessonResponse mark(UUID userId, String slug);

    /** Takes the mark off a published lesson; a lesson without one is left as it is. */
    void unmark(UUID userId, String slug);
}

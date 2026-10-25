package com.cryptopilot.academy.service.impl;

import com.cryptopilot.academy.dto.response.AcademyProgressResponse;
import com.cryptopilot.academy.dto.response.CompletedLessonResponse;
import com.cryptopilot.academy.model.CompletedLesson;
import com.cryptopilot.academy.model.LessonTotals;
import com.cryptopilot.academy.repository.AcademyProgressRepository;
import com.cryptopilot.academy.service.AcademyProgressService;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Trader's progress in the Academy. The instant of a mark comes from the injected clock.
 *
 * <p>Rule: T-114.
 */
@Service
@RequiredArgsConstructor
public class AcademyProgressServiceImpl implements AcademyProgressService {

    private final AcademyProgressRepository progress;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AcademyProgressResponse progress(UUID userId) {
        List<CompletedLesson> completed = progress.completed(userId);
        LessonTotals totals = progress.publishedTotals();
        return new AcademyProgressResponse(
                completed.stream()
                        .map(lesson -> new CompletedLessonResponse(lesson.slug(), lesson.completedAt()))
                        .toList(),
                totals.published(),
                completed.stream().filter(CompletedLesson::onPath).count(),
                totals.onPath());
    }

    @Override
    @Transactional
    public CompletedLessonResponse mark(UUID userId, String slug) {
        UUID articleId = publishedArticle(slug);
        Instant at = progress.mark(UuidV7.next(), userId, articleId, clock.instant());
        return new CompletedLessonResponse(slug, at);
    }

    @Override
    @Transactional
    public void unmark(UUID userId, String slug) {
        progress.unmark(userId, publishedArticle(slug));
    }

    private UUID publishedArticle(String slug) {
        return progress.publishedArticleId(slug).orElseThrow(() -> new ResourceNotFoundException("Article", slug));
    }
}

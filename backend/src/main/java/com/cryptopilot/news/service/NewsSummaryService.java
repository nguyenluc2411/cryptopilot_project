package com.cryptopilot.news.service;

import com.cryptopilot.news.model.SummaryRun;
import java.util.Optional;
import java.util.UUID;

/**
 * NSF-11: the AI summary and sentiment of the articles that have none yet, through the AI Service. Implemented by
 * {@link com.cryptopilot.news.service.impl.NewsSummaryServiceImpl}.
 *
 * <p>Rule: NSF-11; BR-48; D-128.
 */
public interface NewsSummaryService {

    /**
     * One run: the newest articles without a summary, at most the configured number. An article whose summary cannot
     * be produced or does not pass the checks keeps none and is taken up again at the next run, until it has been tried
     * the configured number of times (D-128).
     */
    SummaryRun summarizeDue();

    /**
     * Summarizes one article now, an administrator having asked (T-080b): its summary is replaced when it has one, and
     * neither its age nor its attempts stop it. A refused answer counts as an attempt.
     *
     * @return why the answer was refused, or empty when the summary was stored
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such article
     * @throws com.cryptopilot.common.exception.BusinessException {@code NEWS_SUMMARY_UNAVAILABLE} when the AI
     *     Service is not configured, cannot be reached or is rate limited
     */
    Optional<String> summarizeNow(UUID articleId);
}

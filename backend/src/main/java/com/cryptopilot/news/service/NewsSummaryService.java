package com.cryptopilot.news.service;

import com.cryptopilot.news.model.SummaryRun;

/**
 * NSF-11: the AI summary and sentiment of the articles that have none yet, through the AI Service. Implemented by
 * {@link com.cryptopilot.news.service.impl.NewsSummaryServiceImpl}.
 *
 * <p>Rule: NSF-11; BR-48.
 */
public interface NewsSummaryService {

    /**
     * One run: the newest articles without a summary, at most the configured number. An article whose summary cannot
     * be produced or does not pass the checks keeps none and is taken up again at the next run.
     */
    SummaryRun summarizeDue();
}

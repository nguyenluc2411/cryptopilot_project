package com.cryptopilot.journal.service;

import com.cryptopilot.journal.dto.request.JournalReviewRequest;
import com.cryptopilot.journal.dto.response.ChartImageUploadResponse;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import java.util.UUID;

/**
 * The Trader's review of one of their trades (SCR-21): strategy, texts and one chart image, for SIMULATED and MANUAL
 * trades alike; the execution facts are never changed here (BR-40). Another Trader's trade is "not found" (MSG41).
 * Implemented by {@link com.cryptopilot.journal.service.impl.JournalReviewServiceImpl}.
 *
 * <p>Rule: BR-40; UC-23; SRS 3.6.1.
 */
public interface JournalReviewService {

    /**
     * Replaces the review and answers the trade as SCR-21 shows it; the client then shows MSG14.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41, for an unknown id and another Trader's
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01 naming an execution fact or any other
     *     field that is not part of the review, MSG15 for an unknown strategy, MSG32 for a chart image that is not a
     *     new upload of the caller in an accepted format and size
     * @throws com.cryptopilot.common.exception.BusinessException {@code MEDIA_STORAGE_UNAVAILABLE} when the image
     *     cannot be checked
     */
    JournalEntryDetailResponse review(UUID userId, UUID journalId, JournalReviewRequest request);

    /**
     * A signed form to upload one chart image for the review of the trade.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41, for an unknown id and another Trader's
     * @throws com.cryptopilot.common.exception.BusinessException {@code MEDIA_STORAGE_UNAVAILABLE} without a storage
     */
    ChartImageUploadResponse signChartImage(UUID userId, UUID journalId);
}

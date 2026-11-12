package com.cryptopilot.journal.service;

import com.cryptopilot.journal.dto.request.ManualTradeRequest;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import java.util.UUID;

/**
 * The trades a Trader records by hand (SCR-22): source MANUAL, edited and deleted by their owner only, and kept apart
 * from the simulated ones, whose execution facts nobody edits (BR-40, BR-41). Another Trader's trade is "not found".
 * Implemented by {@link com.cryptopilot.journal.service.impl.ManualTradeServiceImpl}.
 *
 * <p>Rule: BR-40, BR-41; UC-24; SRS 3.6.2.
 */
public interface ManualTradeService {

    /**
     * Records a manual trade.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01, MSG15 or MSG16 naming each field
     */
    JournalEntryDetailResponse record(UUID userId, ManualTradeRequest request);

    /**
     * Replaces every field of one of the user's manual trades; its chart image is kept.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41, for an unknown id and another Trader's
     * @throws com.cryptopilot.common.exception.BusinessException {@code DATA_CONFLICT} (MSG43) for a SIMULATED trade
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG01, MSG15 or MSG16 naming each field
     */
    JournalEntryDetailResponse edit(UUID userId, UUID journalId, ManualTradeRequest request);

    /**
     * Deletes one of the user's manual trades, permanently, with its chart image.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41, for an unknown id and another Trader's
     * @throws com.cryptopilot.common.exception.BusinessException {@code DATA_CONFLICT} (MSG43) for a SIMULATED trade
     */
    void delete(UUID userId, UUID journalId);
}

package com.cryptopilot.journal.service;

/**
 * Corrects the funding of closed simulated trades that closed while a funding settlement was missing. Implemented by
 * {@link com.cryptopilot.journal.service.impl.FundingCorrectionServiceImpl}.
 *
 * <p>Rule: BR-37, BR-38; A-05; D-97 note of 2026-10-24.
 */
public interface FundingCorrectionService {

    /**
     * Asks again for the funding of up to {@code batchSize} trades still marked incomplete, the oldest exits first, and
     * stores every answer that differs: the known sum, the flag, cleared once complete, and the missing times.
     *
     * @return how many trades were changed
     */
    int correctIncompleteFunding(int batchSize);
}

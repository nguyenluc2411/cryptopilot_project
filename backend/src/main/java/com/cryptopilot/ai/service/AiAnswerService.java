package com.cryptopilot.ai.service;

import java.util.UUID;

/**
 * Answers the queued questions (NSF-16, T-073); implemented by
 * {@link com.cryptopilot.ai.service.impl.AiAnswerServiceImpl}.
 *
 * <p>Rule: NSF-16, BR-50, BR-51; SRS 3.9.1, 3.9.2; TECHNICAL_DESIGN 7.14 steps 2–5.
 */
public interface AiAnswerService {

    /**
     * Sends a pending question to the AI Service with the active configuration and the conversation's recent turns,
     * then stores the guarded answer COMPLETED, or marks the question FAILED (no quota used). A question that is no
     * longer pending — answered, failed or swept — is left alone, and so is an answer that arrives after it was swept.
     * The outcome is pushed to the Trader.
     */
    void answer(UUID questionId);

    /**
     * Fails every question left pending past the answer timeout (SRS 3.9.1: 60 seconds) and pushes the outcome.
     *
     * @return how many were failed
     */
    int failUnanswered();
}

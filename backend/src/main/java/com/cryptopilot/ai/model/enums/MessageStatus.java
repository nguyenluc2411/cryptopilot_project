package com.cryptopilot.ai.model.enums;

/**
 * Where an AI message stands ({@code ck_ai_message_status}, SRS 3.9.1). A question is PENDING until its answer is
 * stored (COMPLETED) or it fails or goes unanswered for 60 seconds (FAILED, MSG31, no quota used). An answer is
 * always stored COMPLETED.
 */
public enum MessageStatus {
    PENDING,
    COMPLETED,
    FAILED
}

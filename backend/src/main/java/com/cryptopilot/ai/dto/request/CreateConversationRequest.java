package com.cryptopilot.ai.dto.request;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * "New conversation" on SCR-29, or "Ask AI" on SCR-10, SCR-11 or SCR-18 (SRS 3.9.1).
 *
 * @param pairId the pair the conversation is about, or {@code null} for a general one
 * @param title up to 100 characters, or {@code null} to name it after its first question
 */
public record CreateConversationRequest(
        UUID pairId, @Size(max = 100, message = "MSG01") String title) {}

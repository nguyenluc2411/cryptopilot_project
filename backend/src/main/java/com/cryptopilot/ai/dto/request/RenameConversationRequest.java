package com.cryptopilot.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Renames a conversation (SRS 3.9.1).
 *
 * @param title 1–100 characters
 */
public record RenameConversationRequest(
        @NotBlank(message = "MSG01") @Size(max = 100, message = "MSG01")
        String title) {}

package com.cryptopilot.ai.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of the AI configuration as SCR-42 lists it (UC-50).
 *
 * <p>Rule: UC-50; SRS 3.11.7.
 *
 * @param id the version
 * @param modelName the provider's model id
 * @param systemPrompt the rules the assistant answers by
 * @param temperature from 0 to 1
 * @param maxOutputTokens from 256 to 4096
 * @param disclaimer the sentence every answer ends with
 * @param active whether the assistant uses this version
 * @param createdAt when the version was added
 * @param updatedAt when it was last activated or deactivated
 * @param updatedBy the administrator who last changed it, or {@code null} for the seeded version
 */
public record AiConfigurationResponse(
        UUID id,
        String modelName,
        String systemPrompt,
        BigDecimal temperature,
        int maxOutputTokens,
        String disclaimer,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy) {}

package com.cryptopilot.ai.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one version of the AI configuration holds (UC-50).
 *
 * <p>Rule: UC-50; BR-51; SRS 3.11.7.
 *
 * @param modelName the provider's model id
 * @param systemPrompt the rules the assistant answers by
 * @param temperature from 0 to 1
 * @param maxOutputTokens from 256 to 4096
 * @param disclaimer the sentence every answer ends with
 */
public record AiConfigurationSettings(
        String modelName, String systemPrompt, BigDecimal temperature, int maxOutputTokens, String disclaimer) {

    public AiConfigurationSettings {
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(temperature, "temperature");
        Objects.requireNonNull(disclaimer, "disclaimer");
    }
}

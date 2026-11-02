package com.cryptopilot.ai.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A new version of the AI configuration (SCR-42, UC-50). It is saved inactive; activating it is a second step.
 *
 * <p>Rule: UC-50; SRS 3.11.7.
 *
 * @param modelName the provider's model id: letters, digits, dot, dash and underscore, at most 100 characters
 * @param systemPrompt the rules the assistant answers by, at most 20,000 characters
 * @param temperature from 0 to 1, two decimals
 * @param maxOutputTokens from 256 to 4096
 * @param disclaimer the sentence every answer ends with, at most 500 characters
 */
public record AiConfigurationRequest(
        @NotBlank(message = "MSG01")
        @Size(max = 100, message = "MSG15")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "MSG15")
        String modelName,

        @NotBlank(message = "MSG01") @Size(max = 20000, message = "MSG15")
        String systemPrompt,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", message = "MSG15")
        @DecimalMax(value = "1", message = "MSG15")
        @Digits(integer = 1, fraction = 2, message = "MSG15")
        BigDecimal temperature,

        @NotNull(message = "MSG01") @Min(value = 256, message = "MSG15") @Max(value = 4096, message = "MSG15")
        Integer maxOutputTokens,

        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG15")
        String disclaimer) {}

package com.cryptopilot.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The AI Assistant's pipeline, under {@code cryptopilot.ai.assistant} (NSF-16).
 *
 * @param historyTurns how many earlier turns go with a question (TECHNICAL_DESIGN 7.14 step 2: at most 10, the AI
 *     Service's own cap)
 * @param historyTurnChars each earlier turn is cut to this many characters, so a long answer does not cost its tokens
 *     again with every later question
 * @param answerTimeout how long a question may wait for its answer before it fails (SRS 3.9.1: 60 seconds)
 * @param sweepEnabled whether the sweep that fails questions left pending runs
 * @param sweepInterval how often that sweep runs
 */
@Validated
@ConfigurationProperties("cryptopilot.ai.assistant")
public record AiAssistantProperties(
        @Min(0) @Max(10) @DefaultValue("10") int historyTurns,
        @Min(200) @Max(8000) @DefaultValue("2000") int historyTurnChars,
        @NotNull @DefaultValue("60s") Duration answerTimeout,
        @DefaultValue("true") boolean sweepEnabled,
        @NotNull @DefaultValue("15s") Duration sweepInterval) {}

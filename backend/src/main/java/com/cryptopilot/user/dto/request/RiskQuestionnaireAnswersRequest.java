package com.cryptopilot.user.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * The answers to the risk questionnaire of SCR-07 (SRS 3.2.5): the chosen answer code by question code, every question
 * answered.
 *
 * <p>Rule: BR-66; SRS 3.2.5; message MSG01.
 *
 * @param answers answer code by question code
 */
public record RiskQuestionnaireAnswersRequest(
        @NotNull(message = "MSG01") Map<String, String> answers) {}

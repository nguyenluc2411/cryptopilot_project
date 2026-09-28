package com.cryptopilot.user.dto.response;

import java.util.List;

/**
 * The risk questionnaire of SCR-07, in the order it is asked (SRS 3.2.5).
 *
 * @param questions the questions
 */
public record RiskQuestionnaireResponse(List<RiskQuestionResponse> questions) {}

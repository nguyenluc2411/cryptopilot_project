package com.cryptopilot.user.dto.response;

import com.cryptopilot.user.model.QuestionGroup;
import java.util.List;

/**
 * One question of the risk questionnaire (SRS 3.2.5).
 *
 * @param code the question code, the key of its answer
 * @param group CAPACITY or ATTITUDE
 * @param text the question as shown
 * @param options the answers, from least to most risk
 */
public record RiskQuestionResponse(
        String code, QuestionGroup group, String text, List<RiskAnswerOptionResponse> options) {}

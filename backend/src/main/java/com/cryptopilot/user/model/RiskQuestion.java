package com.cryptopilot.user.model;

import com.cryptopilot.user.model.enums.QuestionGroup;
import java.util.List;

/**
 * One question of the risk questionnaire.
 *
 * @param code the question's code, the key of its answer
 * @param group the group whose score it counts towards
 * @param text the question as shown
 * @param options the answers, from least to most risk
 */
public record RiskQuestion(String code, QuestionGroup group, String text, List<RiskAnswerOption> options) {

    public RiskQuestion {
        options = List.copyOf(options);
    }
}

package com.cryptopilot.user.model;

/**
 * One answer to a question of the risk questionnaire.
 *
 * @param code the answer's code within its question
 * @param text the answer as shown
 * @param points 1 (least risk) to 3 (most risk)
 */
public record RiskAnswerOption(String code, String text, int points) {}

package com.cryptopilot.user.dto.response;

/**
 * One answer of a questionnaire question, as the client shows it. Its points are not sent: the client submits the
 * code and the server scores it.
 *
 * @param code the answer code to submit
 * @param text the answer as shown
 */
public record RiskAnswerOptionResponse(String code, String text) {}

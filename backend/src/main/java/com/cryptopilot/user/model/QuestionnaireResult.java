package com.cryptopilot.user.model;

import com.cryptopilot.user.RiskProfile;

/**
 * What a completed risk questionnaire suggests.
 *
 * @param capacityScore the points of the capacity group
 * @param attitudeScore the points of the attitude group
 * @param suggested the profile the answers point to; the Trader may choose another
 */
public record QuestionnaireResult(int capacityScore, int attitudeScore, RiskProfile suggested) {}

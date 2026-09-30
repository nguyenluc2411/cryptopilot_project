package com.cryptopilot.user.dto.response;

import com.cryptopilot.user.model.enums.RiskProfile;

/**
 * The profile a completed questionnaire suggests (SRS 3.2.5). Nothing is saved: the Trader may choose another.
 *
 * @param capacityScore the points of the capacity group, 3–9
 * @param attitudeScore the points of the attitude group, 3–9
 * @param suggestedProfile the suggested profile
 */
public record RiskSuggestionResponse(int capacityScore, int attitudeScore, RiskProfile suggestedProfile) {}

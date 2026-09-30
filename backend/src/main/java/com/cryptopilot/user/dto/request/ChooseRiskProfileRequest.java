package com.cryptopilot.user.dto.request;

import com.cryptopilot.user.model.enums.RiskProfile;
import jakarta.validation.constraints.NotNull;

/**
 * The risk profile a Trader chooses on the Profile tab of SCR-07 (SRS 3.2.5, BR-66).
 *
 * <p>Rule: BR-66; SRS 3.2.5; messages MSG01, MSG48.
 *
 * @param riskProfile the chosen profile
 * @param confirmAggressive {@code true} once the Trader has confirmed MSG48; required only when switching to
 *     AGGRESSIVE
 */
public record ChooseRiskProfileRequest(
        @NotNull(message = "MSG01") RiskProfile riskProfile, boolean confirmAggressive) {}

package com.cryptopilot.user.service;

import com.cryptopilot.user.RiskProfile;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.dto.response.RiskProfileResponse;
import com.cryptopilot.user.dto.response.RiskQuestionnaireResponse;
import com.cryptopilot.user.dto.response.RiskSuggestionResponse;
import java.util.Map;
import java.util.UUID;

/**
 * The use cases of {@link com.cryptopilot.user.service.impl.RiskProfileServiceImpl}: the risk questionnaire and the
 * risk profile of the Profile tab (D-48).
 *
 * <p>Rule: BR-66; SRS 3.2.5, UC-06; D-53.
 */
public interface RiskProfileService extends RiskProfileApi {

    /** The questionnaire, in the order it is asked. */
    RiskQuestionnaireResponse questionnaire();

    /**
     * The profile a completed questionnaire suggests; nothing is saved.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) when a question is
     *     unanswered or an answer is unknown
     */
    RiskSuggestionResponse suggest(Map<String, String> answers);

    /** The Trader's risk profile and its parameters. */
    RiskProfileResponse riskProfileOf(UUID userId);

    /**
     * Saves the Trader's choice of risk profile.
     *
     * @param confirmAggressive whether MSG48 was confirmed; needed only when switching to AGGRESSIVE
     * @throws com.cryptopilot.common.exception.BusinessException {@code RISK_PROFILE_CONFIRMATION_REQUIRED} (MSG48)
     *     when switching to AGGRESSIVE unconfirmed
     */
    void chooseRiskProfile(UUID userId, RiskProfile riskProfile, boolean confirmAggressive);
}

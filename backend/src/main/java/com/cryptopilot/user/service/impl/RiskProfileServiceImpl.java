package com.cryptopilot.user.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.RiskProfileParameters;
import com.cryptopilot.user.calculator.RiskQuestionnaire;
import com.cryptopilot.user.config.RiskProfileProperties;
import com.cryptopilot.user.dto.response.RiskAnswerOptionResponse;
import com.cryptopilot.user.dto.response.RiskProfileResponse;
import com.cryptopilot.user.dto.response.RiskQuestionResponse;
import com.cryptopilot.user.dto.response.RiskQuestionnaireResponse;
import com.cryptopilot.user.dto.response.RiskSuggestionResponse;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.model.QuestionnaireResult;
import com.cryptopilot.user.model.enums.RiskProfile;
import com.cryptopilot.user.repository.UserProfileRepository;
import com.cryptopilot.user.service.RiskProfileService;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The risk profile of the Profile tab: the questionnaire that suggests one, the Trader's choice, and the configured
 * parameters other modules read through {@link com.cryptopilot.user.RiskProfileApi}.
 *
 * <p>The suggestion is only a suggestion (SRS 3.2.5): it saves nothing, and the Trader chooses separately. Switching to
 * AGGRESSIVE needs the confirmation of MSG48; staying on it, or choosing a lower profile, does not.
 *
 * <p>Rule: BR-66; SRS 3.2.5, UC-06; messages MSG01, MSG48; D-53; Q-24.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley ("Service
 * Layer").
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class RiskProfileServiceImpl implements RiskProfileService {

    private final UserProfileRepository profiles;
    private final RiskProfileProperties properties;

    @Override
    public RiskQuestionnaireResponse questionnaire() {
        return new RiskQuestionnaireResponse(RiskQuestionnaire.questions().stream()
                .map(question -> new RiskQuestionResponse(
                        question.code(),
                        question.group(),
                        question.text(),
                        question.options().stream()
                                .map(option -> new RiskAnswerOptionResponse(option.code(), option.text()))
                                .toList()))
                .toList());
    }

    @Override
    public RiskSuggestionResponse suggest(Map<String, String> answers) {
        QuestionnaireResult result;
        try {
            result = RiskQuestionnaire.score(answers);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        return new RiskSuggestionResponse(result.capacityScore(), result.attitudeScore(), result.suggested());
    }

    @Override
    @Transactional(readOnly = true)
    public RiskProfileResponse riskProfileOf(UUID userId) {
        RiskProfileParameters parameters =
                properties.parameters(profileRowOf(userId).getRiskProfile());
        return new RiskProfileResponse(
                parameters.profile(),
                parameters.riskPerTradePercent(),
                parameters.maxFuturesLeverage(),
                parameters.maxTotalOpenRiskPercent());
    }

    @Override
    @Transactional
    public void chooseRiskProfile(UUID userId, RiskProfile riskProfile, boolean confirmAggressive) {
        UserProfile profile = profileRowOf(userId);
        if (riskProfile == RiskProfile.AGGRESSIVE
                && profile.getRiskProfile() != RiskProfile.AGGRESSIVE
                && !confirmAggressive) {
            throw new BusinessException(
                    ErrorCode.RISK_PROFILE_CONFIRMATION_REQUIRED, "switching to AGGRESSIVE needs confirmation");
        }
        profile.chooseRiskProfile(riskProfile);
        profiles.save(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public RiskProfileParameters parametersOf(UUID userId) {
        RiskProfile chosen =
                profiles.findById(userId).map(UserProfile::getRiskProfile).orElse(RiskProfile.DEFAULT);
        return properties.parameters(chosen);
    }

    private UserProfile profileRowOf(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserProfile", userId));
    }
}

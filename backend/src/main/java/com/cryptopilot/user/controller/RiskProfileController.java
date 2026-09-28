package com.cryptopilot.user.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.MessageResponse;
import com.cryptopilot.user.dto.request.ChooseRiskProfileRequest;
import com.cryptopilot.user.dto.request.RiskQuestionnaireAnswersRequest;
import com.cryptopilot.user.dto.response.RiskProfileResponse;
import com.cryptopilot.user.dto.response.RiskQuestionnaireResponse;
import com.cryptopilot.user.dto.response.RiskSuggestionResponse;
import com.cryptopilot.user.service.RiskProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The risk profile and risk questionnaire of the Profile tab of SCR-07 (SRS 3.2.5, UC-06). Under {@code /me}, so the
 * account is always the token's subject and the filter chain confines the paths to the Trader role.
 *
 * <p>Rule: BR-66; SRS UC-06, section 3.2.5; messages MSG01, MSG14, MSG48; TECHNICAL_DESIGN section 8.
 */
@Tag(name = "Risk profile", description = "Risk profile and risk questionnaire of SCR-07 (UC-06, BR-66). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class RiskProfileController {

    private final RiskProfileService riskProfiles;

    @Operation(summary = "Read the risk questionnaire (SRS 3.2.5)")
    @ApiResponse(responseCode = "200", description = "The questions and their answers")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/risk-questionnaire")
    public RiskQuestionnaireResponse questionnaire() {
        return riskProfiles.questionnaire();
    }

    @Operation(summary = "Score the risk questionnaire; suggests a profile and saves nothing (SRS 3.2.5)")
    @ApiResponse(responseCode = "200", description = "The suggested profile")
    @ApiResponse(responseCode = "400", description = "MSG01: a question unanswered or an answer unknown")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PostMapping("/risk-questionnaire")
    public RiskSuggestionResponse suggest(@Valid @RequestBody RiskQuestionnaireAnswersRequest request) {
        return riskProfiles.suggest(request.answers());
    }

    @Operation(summary = "Read the risk profile and its parameters (BR-66)")
    @ApiResponse(responseCode = "200", description = "The risk profile")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/risk-profile")
    public RiskProfileResponse riskProfile(@AuthenticationPrincipal Jwt caller) {
        return riskProfiles.riskProfileOf(ProfileController.accountOf(caller));
    }

    @Operation(summary = "Choose the risk profile (BR-66); AGGRESSIVE needs the confirmation of MSG48")
    @ApiResponse(responseCode = "200", description = "MSG14: saved")
    @ApiResponse(responseCode = "400", description = "MSG01: the body is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "409", description = "MSG48: switching to AGGRESSIVE needs confirmation")
    @PutMapping("/risk-profile")
    public MessageResponse chooseRiskProfile(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody ChooseRiskProfileRequest request) {
        riskProfiles.chooseRiskProfile(
                ProfileController.accountOf(caller), request.riskProfile(), request.confirmAggressive());
        return new MessageResponse("MSG14");
    }
}

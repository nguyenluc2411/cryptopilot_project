package com.cryptopilot.user.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.MessageResponse;
import com.cryptopilot.user.dto.request.NotificationPreferencesRequest;
import com.cryptopilot.user.dto.request.UpdateProfileRequest;
import com.cryptopilot.user.dto.response.ProfileResponse;
import com.cryptopilot.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Profile and Notifications tabs of SCR-07 (SRS UC-06, UC-08, section 3.2.5).
 *
 * <p>Every path is under {@code /me}, and the account is the subject of the access token, never a
 * value the request carries. So there is no identifier in the URL to change and no other account's
 * profile a request could reach, which is how SRS 4.2.4's ownership check holds here without a line
 * of code comparing owners. The filter chain confines {@code /me/**} to the Trader role; an
 * administrator uses only the Security tab (SRS 3.1.3).
 *
 * <p>A save answers MSG14, which SRS 3.2.5 assigns every successful save on this screen.
 *
 * <p>Rule: SRS UC-06, UC-08, sections 3.1.3, 3.2.5 and 4.2.4; messages MSG01, MSG14, MSG15;
 * TECHNICAL_DESIGN section 8.
 */
@Tag(name = "Profile", description = "The Profile and Notifications tabs of SCR-07 (UC-06, UC-08). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ProfileController {

    private final ProfileService profiles;

    /** What the Profile and Notifications tabs show. */
    @Operation(summary = "Read the profile and notification switches (UC-06)")
    @ApiResponse(responseCode = "200", description = "The profile")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/profile")
    public ProfileResponse profile(@AuthenticationPrincipal Jwt caller) {
        return profiles.profileOf(accountOf(caller));
    }

    /** Saves the Profile tab: display name and the trading defaults (UC-06). */
    @Operation(summary = "Save the profile (UC-06)")
    @ApiResponse(responseCode = "200", description = "MSG14: saved")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PutMapping("/profile")
    public MessageResponse updateProfile(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody UpdateProfileRequest request) {
        profiles.updateProfile(accountOf(caller), request);
        return new MessageResponse("MSG14");
    }

    /** Saves the Notifications tab: the mail and push switches (UC-08). */
    @Operation(summary = "Save the notification switches (UC-08)")
    @ApiResponse(responseCode = "200", description = "MSG14: saved")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PutMapping("/notification-preferences")
    public MessageResponse updateNotificationPreferences(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody NotificationPreferencesRequest request) {
        profiles.updateNotificationPreferences(accountOf(caller), request.email(), request.push());
        return new MessageResponse("MSG14");
    }

    /** The account the access token was issued to; its subject is the account key. */
    static UUID accountOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

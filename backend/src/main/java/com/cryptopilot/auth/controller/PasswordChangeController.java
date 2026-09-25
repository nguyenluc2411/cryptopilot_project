package com.cryptopilot.auth.controller;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.auth.dto.request.ChangePasswordRequest;
import com.cryptopilot.auth.service.PasswordChangeService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.MessageResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Security tab of SCR-07: changing the password of the signed-in account (SRS UC-07, section
 * 3.2.5).
 *
 * <p>It is under {@code /me} with the other SCR-07 tabs, but it is an {@code auth} endpoint, because
 * the encoder and BR-02 are here and {@code user} never sees a password. It is the one SCR-07 path
 * both roles reach — the filter chain names it before the Trader-only {@code /me/**} rule — since
 * an administrator has a password of their own to change (SRS 3.1.3).
 *
 * <p>The account and the session come from the access token: its subject and its {@code sid} claim.
 * Neither can be named in the request, so a caller can change no password but their own.
 *
 * <p>Rule: SRS UC-07, sections 3.1.3, 3.2.5 and 4.2.4; messages MSG01, MSG03, MSG08, MSG14;
 * TECHNICAL_DESIGN section 8.
 */
@Tag(name = "Account", description = "The Security tab of SCR-07 (UC-07). Trader or Admin.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PasswordChangeController {

    private final PasswordChangeService passwordChange;

    /** Changes the password; MSG14 on success, as every save on SCR-07 (SRS 3.2.5). */
    @Operation(summary = "Change the password of the signed-in account (UC-07)")
    @ApiResponse(responseCode = "200", description = "MSG14: saved; the other sessions are revoked")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG03 or MSG08: invalid body, weak password, or wrong current password")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @PutMapping("/api/v1/me/password")
    public MessageResponse changePassword(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody ChangePasswordRequest request) {
        passwordChange.changePassword(
                UUID.fromString(caller.getSubject()),
                sessionOf(caller),
                request.currentPassword(),
                request.newPassword());
        return new MessageResponse("MSG14");
    }

    /** The session the token was issued within, or {@code null} for a token issued before the claim. */
    private static UUID sessionOf(Jwt caller) {
        String sid = caller.getClaimAsString(JwtConfig.SESSION_CLAIM);
        return sid == null ? null : UUID.fromString(sid);
    }
}

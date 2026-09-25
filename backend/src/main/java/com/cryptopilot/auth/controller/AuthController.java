package com.cryptopilot.auth.controller;

import com.cryptopilot.auth.dto.request.ForgotPasswordRequest;
import com.cryptopilot.auth.dto.request.LoginRequest;
import com.cryptopilot.auth.dto.request.LogoutRequest;
import com.cryptopilot.auth.dto.request.RefreshRequest;
import com.cryptopilot.auth.dto.request.RegisterRequest;
import com.cryptopilot.auth.dto.request.ResendVerificationRequest;
import com.cryptopilot.auth.dto.request.ResetPasswordRequest;
import com.cryptopilot.auth.dto.request.VerifyEmailRequest;
import com.cryptopilot.auth.dto.response.SessionResponse;
import com.cryptopilot.auth.model.IssuedSession;
import com.cryptopilot.auth.service.AuthService;
import com.cryptopilot.common.web.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints a visitor reaches before they have a session: registering, verifying an address and
 * the resend between them, then signing in, renewing a session and signing out.
 *
 * <p>It validates a body, calls one service method and names the message the client should show. It
 * opens no transaction — the service knows how many tables a request touches and this class does
 * not — and it touches no repository and no entity, which is what the layer rules require and what
 * keeps the mapping from leaking into the API.
 *
 * <p>No response carries a password, and only the sign-in and the refresh carry a token — the two
 * requests whose whole purpose is to hand one over. The verification link is not among them: it
 * travels by mail and exists in no response body, so it cannot be read out of a browser's network tab
 * or a proxy log.
 *
 * <p>All six are open in the filter chain, and that is not a hole: each is reached by someone who has
 * no session yet, or whose session is what the request is about. Signing out is open for the same
 * reason — the refresh token in the body is the credential, and requiring a valid access token as
 * well would mean a client whose access token expired five minutes ago could not end its session.
 *
 * <p>Rule: SRS UC-01, UC-02, UC-03, UC-05, sections 3.2.1, 3.2.2 and 3.2.3; messages MSG05, MSG06;
 * TECHNICAL_DESIGN sections 3.1, 5.3 and 8.
 */
@Tag(
        name = "Auth",
        description = "Registration, verification, sign-in, sessions and password reset (UC-01 to UC-05). Public.")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AuthController {

    private final AuthService authService;

    /**
     * Registers an account and sends a verification link (SRS UC-01).
     *
     * <p>201, because a request that succeeds has created an account. MSG05 is what the client
     * shows; it names the address, which the client already has.
     */
    @Operation(summary = "Register a Trader account (UC-01)")
    @ApiResponse(responseCode = "201", description = "Account created; a verification mail is sent")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01 or MSG03: invalid body, or the password breaks the policy (BR-02)")
    @ApiResponse(responseCode = "409", description = "MSG04: the address is registered")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.email(), request.password(), request.displayName());
        return new MessageResponse("MSG05");
    }

    /**
     * Verifies an address from the link that was mailed out (SRS UC-02).
     *
     * <p>The token is in the body rather than the path, because a value in a URL is written to
     * every access log between the browser and the server.
     */
    @Operation(summary = "Verify an address from the mailed link (UC-02)")
    @ApiResponse(responseCode = "200", description = "Address verified")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG07: invalid, used or expired link")
    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return new MessageResponse("MSG06");
    }

    /**
     * Sends the verification link again (SRS 3.2.2).
     *
     * <p>202 and the same body whatever the address turns out to be. An endpoint that takes an
     * address and answers differently depending on whether an account holds it is a way to test
     * addresses, so this one accepts the request and says only that it was accepted — whether a
     * mail follows depends on things the caller is not told.
     */
    @Operation(summary = "Send the verification link again (UC-02)")
    @ApiResponse(responseCode = "202", description = "Accepted; the same answer whatever the address holds")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return new MessageResponse("MSG05");
    }

    /**
     * Signs in and opens a session (SRS UC-03, section 3.2.3).
     *
     * <p>200 and not 201: a session is not a resource this API lets anybody address, and there is no
     * location to give. A refusal carries the message SRS 5.3 assigns — MSG08, MSG09, MSG10 or
     * MSG11 — and the status its {@code ErrorCode} names.
     */
    @Operation(summary = "Sign in and open a session (UC-03)")
    @ApiResponse(responseCode = "200", description = "Session opened")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(responseCode = "401", description = "MSG08: wrong address or password")
    @ApiResponse(responseCode = "403", description = "MSG10 or MSG11: account not active or not verified")
    @ApiResponse(responseCode = "429", description = "MSG09: locked for 15 minutes after 5 failures (BR-03)")
    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request) {
        return sessionResponse(authService.login(request.email(), request.password(), request.rememberMe()));
    }

    /**
     * Exchanges a refresh token for a new pair (SRS 3.2.3, TECHNICAL_DESIGN 7.15).
     *
     * <p>The token presented stops working the moment this succeeds, so a client replaces both values
     * it holds with both values it receives. A client that keeps the old one and presents it again is
     * indistinguishable from an attacker replaying a copy, and is answered MSG44 with its whole family
     * revoked.
     */
    @Operation(summary = "Rotate the refresh token (UC-05)")
    @ApiResponse(responseCode = "200", description = "New token pair")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: the refresh token is not a live session")
    @PostMapping("/refresh")
    public SessionResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return sessionResponse(authService.refresh(request.refreshToken()));
    }

    /**
     * Ends the session the presented refresh token belongs to (SRS UC-05, section 3.2.3).
     *
     * <p>204 and an empty body, always — whether the token was valid, already spent or never existed.
     * A logout that reported which of those it was would be a way to test refresh tokens, and the
     * caller has nothing to do differently either way.
     */
    /**
     * SCR-05. Answers MSG12 whether or not the address holds an account, which SRS 3.2.4 requires
     * verbatim: any other answer would turn this into a way of asking who has registered.
     *
     * <p>202 rather than 200, and for the same reason the resend endpoint uses it: what was accepted
     * is the request, and whether anything is mailed depends on facts the caller is not told.
     */
    @Operation(summary = "Request a password reset link (UC-04)")
    @ApiResponse(responseCode = "202", description = "Accepted; the same answer whatever the address holds")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request.email());
        return new MessageResponse("MSG12");
    }

    /**
     * SCR-06. MSG13 on success, after which SRS 3.2.4 sends the person back to SCR-04 to sign in
     * again - which they have to, because the reset revoked every session (BR-04).
     */
    @Operation(summary = "Set a new password from the reset link (UC-04)")
    @ApiResponse(responseCode = "200", description = "Password replaced; sessions revoked (BR-04)")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG03 or MSG07: invalid body, weak password, or invalid link")
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return new MessageResponse("MSG13");
    }

    @Operation(summary = "End the session (UC-05)")
    @ApiResponse(responseCode = "204", description = "Session ended")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken(), request.fcmToken());
    }

    /**
     * The one place a session becomes a response body. It drops the account key the service carries,
     * because a sign-in response is not a way to read anything about the account.
     */
    private static SessionResponse sessionResponse(IssuedSession session) {
        return new SessionResponse(
                session.accessToken(),
                session.accessTokenExpiresAt(),
                session.refreshToken(),
                session.refreshTokenExpiresAt(),
                session.role());
    }
}

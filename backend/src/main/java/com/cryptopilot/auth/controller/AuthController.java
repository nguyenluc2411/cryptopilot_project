package com.cryptopilot.auth.controller;

import com.cryptopilot.auth.dto.MessageResponse;
import com.cryptopilot.auth.dto.RegisterRequest;
import com.cryptopilot.auth.dto.ResendVerificationRequest;
import com.cryptopilot.auth.dto.VerifyEmailRequest;
import com.cryptopilot.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints a visitor reaches before they have an account, and the resend between them.
 *
 * <p>It validates a body, calls one service method and names the message the client should show. It
 * opens no transaction — the service knows how many tables a request touches and this class does
 * not — and it touches no repository and no entity, which is what the layer rules require and what
 * keeps the mapping from leaking into the API.
 *
 * <p>Nothing it returns carries a password or a token. Registration answers that it happened;
 * verification answers that it happened; the link itself travels by mail and exists in no response
 * body, so it cannot be read out of a browser's network tab or a proxy log.
 *
 * <p>Rule: SRS UC-01, UC-02, sections 3.2.1 and 3.2.2; messages MSG05, MSG06; TECHNICAL_DESIGN
 * sections 3.1 and 8.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registers an account and sends a verification link (SRS UC-01).
     *
     * <p>201, because a request that succeeds has created an account. MSG05 is what the client
     * shows; it names the address, which the client already has.
     */
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
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return new MessageResponse("MSG05");
    }
}

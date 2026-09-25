package com.cryptopilot.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * The value taken from a verification link (SRS 3.2.2).
 *
 * <p>It is a secret, so it arrives in a body rather than in the query string of a URL that would be
 * written to every access log and proxy on the way.
 *
 * <p>Rule: BR-01; SRS 3.2.2, message MSG01.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "MSG01") String token) {}

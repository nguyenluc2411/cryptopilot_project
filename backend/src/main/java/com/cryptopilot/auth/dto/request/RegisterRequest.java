package com.cryptopilot.auth.dto.request;

import com.cryptopilot.auth.PasswordPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What SCR-02 sends to register (SRS 3.2.1).
 *
 * <p>Each message is the SRS <em>code</em>, not its text. The backend never duplicates a message of
 * SRS section 5.3: the client owns the wording and picks it by code, which is what keeps one
 * sentence from existing in two repositories and drifting.
 *
 * <p>The rules declared here are the convenience, not the guarantee. Bean validation reports them
 * per field, which is what the web client needs to draw a message under an input; the password
 * policy is enforced again in the service, because that is the path every future way of setting a
 * password also takes.
 *
 * <p>Rule: BR-02; SRS 3.2.1, messages MSG01, MSG02, MSG03.
 */
public record RegisterRequest(
        @NotBlank(message = "MSG01") @Email(message = "MSG02") @Size(max = 255, message = "MSG02")
        String email,

        @NotBlank(message = "MSG01") @Size(min = 2, max = 50, message = "MSG01")
        String displayName,

        @NotBlank(message = "MSG01") @Pattern(regexp = PasswordPolicy.PATTERN, message = "MSG03")
        String password,

        @NotBlank(message = "MSG01") String confirmPassword,
        boolean acceptsDisclaimer) {

    /**
     * SRS 3.2.1 requires the two password fields to match. Section 5.3 has no message for a
     * mismatch, so it is reported under {@code confirmPassword} with the code of the password
     * rule it belongs to; an alignment item asks for one of its own.
     */
    @AssertTrue(message = "MSG03")
    public boolean isConfirmPasswordMatching() {
        return password != null && password.equals(confirmPassword);
    }

    /** SRS 3.2.1: confirming that CryptoPilot does not give investment advice is required. */
    @AssertTrue(message = "MSG01")
    public boolean isAcceptsDisclaimer() {
        return acceptsDisclaimer;
    }
}

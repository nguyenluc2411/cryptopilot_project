package com.cryptopilot.auth.dto;

import com.cryptopilot.auth.PasswordPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What SCR-06 sends to set a new password (SRS 3.2.4).
 *
 * <p>The same arrangement as {@link RegisterRequest}: the rules declared here are the convenience
 * that lets the client draw a message under the right input, and the guarantee is
 * {@link PasswordPolicy} in the service, which every path that sets a password goes through.
 *
 * <p>Rule: BR-02, BR-04; SRS 3.2.4, messages MSG01, MSG03.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "MSG01") String token,

        @NotBlank(message = "MSG01") @Pattern(regexp = PasswordPolicy.PATTERN, message = "MSG03")
        String newPassword,

        @NotBlank(message = "MSG01") String confirmPassword) {

    /**
     * SRS 3.2.4 asks SCR-06 for a new password and its confirmation. Section 5.3 has no message for
     * a mismatch, so it is reported under {@code confirmPassword} with the code of the password rule
     * it belongs to, exactly as registration does; the same alignment item asks for one of its own.
     */
    @AssertTrue(message = "MSG03")
    public boolean isConfirmPasswordMatching() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }

    /**
     * Every component of this record is a credential: the link that authorises the change and the
     * password it sets. A record prints all of them, so none of them is printed.
     */
    @Override
    public String toString() {
        return "ResetPasswordRequest(token=***)";
    }
}

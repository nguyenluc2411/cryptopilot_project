package com.cryptopilot.auth.dto;

import com.cryptopilot.auth.PasswordPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What the Security tab of SCR-07 sends to change a password (SRS 3.2.5, UC-07).
 *
 * <p>The same arrangement as {@link ResetPasswordRequest}, with the current password in place of the
 * link: the rules declared here let the client draw a message under the right input, and the
 * guarantee is {@link PasswordPolicy} in the service. The confirmation mismatch borrows MSG03 exactly
 * as registration and the reset do, because SRS 5.3 has no message of its own for it (A-20).
 *
 * <p>Rule: BR-02; SRS UC-07, section 3.2.5; messages MSG01, MSG03.
 *
 * @param currentPassword the password the account signs in with now
 * @param newPassword the password it will sign in with afterwards
 * @param confirmPassword the new password again
 */
public record ChangePasswordRequest(
        @NotBlank(message = "MSG01") String currentPassword,

        @NotBlank(message = "MSG01") @Pattern(regexp = PasswordPolicy.PATTERN, message = "MSG03")
        String newPassword,

        @NotBlank(message = "MSG01") String confirmPassword) {

    /** SRS 3.2.5 asks for the new password and its confirmation; they have to match. */
    @AssertTrue(message = "MSG03")
    public boolean isConfirmPasswordMatching() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }

    /** Every component is a password, so none of them is printed. */
    @Override
    public String toString() {
        return "ChangePasswordRequest(***)";
    }
}

package com.cryptopilot.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.dto.request.ChangePasswordRequest;
import com.cryptopilot.auth.dto.request.LogoutRequest;
import org.junit.jupiter.api.Test;

/**
 * The request records T-014 added carry credentials, and a record prints every component by
 * default — into a log line, an exception message or a debugger. Each one overrides that, and this is
 * what keeps the override from being dropped in a later edit.
 *
 * <p>Rule: SRS 4.2.4 (credentials are never logged); UC-05, UC-07.
 */
class CredentialRequestsTest {

    @Test
    void UC05_aSignOut_printsNeitherToken() {
        String printed = new LogoutRequest("refresh-secret", "device-secret").toString();

        assertThat(printed).doesNotContain("refresh-secret").doesNotContain("device-secret");
    }

    @Test
    void UC05_aSignOutWithoutADevice_saysSoWithoutPrintingTheRefreshToken() {
        String printed = new LogoutRequest("refresh-secret", null).toString();

        assertThat(printed).doesNotContain("refresh-secret").doesNotContain("fcmToken");
    }

    @Test
    void UC07_aPasswordChange_printsNoPassword() {
        String printed = new ChangePasswordRequest("Current1", "Newpass1", "Newpass1").toString();

        assertThat(printed).doesNotContain("Current1").doesNotContain("Newpass1");
    }
}

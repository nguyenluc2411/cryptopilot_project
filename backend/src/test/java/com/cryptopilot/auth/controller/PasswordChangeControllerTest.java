package com.cryptopilot.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Security tab of SCR-07 over HTTP (SRS UC-07, section 3.2.5): the status and message code of
 * each answer, and that an administrator reaches it as well as a Trader.
 *
 * <p>The rules themselves — which sessions end, what BR-03 does not count — are proved in
 * {@code PasswordChangeTest}; this class proves what a client sees.
 *
 * <p>Rule: BR-02; SRS UC-07, sections 3.1.3 and 3.2.5; messages MSG01, MSG03, MSG08, MSG14.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PasswordChangeControllerTest {

    private static final String TEST_DOMAIN = "@t014pwweb.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final String NEW_PASSWORD = "Zyxwvu9Q";

    private static final String CHANGE = "/api/v1/me/password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** SRS 3.2.5: success shows MSG14, and the new password is the one that signs in. */
    @Test
    void UC07_aValidChange_answersMsg14() throws Exception {
        String token = signedIn("trader");

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("trader", NEW_PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * SRS 3.2.5: a wrong current password shows MSG08 — with 400, not the 401 of a failed sign-in,
     * because the caller's session is fine and a 401 would send the client back to SCR-04.
     */
    @Test
    void UC07_aWrongCurrentPassword_answersMsg08WithBadRequest() throws Exception {
        String token = signedIn("mistypes");

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("Wrong1234", NEW_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG08"))
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
    }

    /** BR-02 on the new password, under its own field, with MSG03. */
    @Test
    void BR02_aWeakNewPassword_answersMsg03UnderItsField() throws Exception {
        String token = signedIn("weak");

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, "weakpass", "weakpass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").value("MSG03"));
    }

    /** The confirmation has to match; the mismatch borrows MSG03 as registration's does (A-20). */
    @Test
    void UC07_aConfirmationThatDoesNotMatch_isRefused() throws Exception {
        String token = signedIn("mismatch");

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD, "Zyxwvu9R")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.confirmPasswordMatching").value("MSG03"));
    }

    /** A missing current password is MSG01. */
    @Test
    void UC07_aMissingCurrentPassword_answersMsg01() throws Exception {
        String token = signedIn("forgot-current");

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("", NEW_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currentPassword").value("MSG01"));
    }

    /**
     * SRS 3.1.3 grants an administrator SCR-07's Security tab: an Admin changes their own password on
     * the same endpoint and is answered the same MSG14.
     */
    @Test
    void UC07_anAdministrator_changesTheirOwnPassword() throws Exception {
        signedIn("admin");
        sql.sql("update user_account set role = 'ADMIN' where email = ?")
                .param("admin" + TEST_DOMAIN)
                .update();
        String adminToken = bearerFrom(login("admin", PASSWORD));

        mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));
    }

    /** The response carries no password, in either direction. */
    @Test
    void UC07_theResponse_carriesNoPassword() throws Exception {
        String token = signedIn("quiet");

        String body = mvc.perform(put(CHANGE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
    }

    private static String changeBody(String current, String next, String confirmation) {
        return "{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\", \"confirmPassword\": \""
                + confirmation + "\"}";
    }

    private static String loginBody(String localPart, String password) {
        return "{\"email\": \"" + localPart + TEST_DOMAIN + "\", \"password\": \"" + password + "\"}";
    }

    private String login(String localPart, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(localPart, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String bearerFrom(String sessionBody) {
        return "Bearer " + JsonPath.read(sessionBody, "$.accessToken");
    }

    /** Registers, verifies and signs in a Trader, and answers the {@code Authorization} header value. */
    private String signedIn(String localPart) throws Exception {
        String email = localPart + TEST_DOMAIN;
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"displayName\": \"Test Person\", \"password\": \""
                                + PASSWORD + "\", \"confirmPassword\": \"" + PASSWORD
                                + "\", \"acceptsDisclaimer\": true}"))
                .andExpect(status().isCreated());
        sql.sql("update user_account set email_verified_at = created_at where email = ?")
                .param(email)
                .update();
        return bearerFrom(login(localPart, PASSWORD));
    }
}

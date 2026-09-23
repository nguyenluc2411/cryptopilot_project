package com.cryptopilot.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Guessing the current password on the Security tab (A-30): the fifth wrong answer in a row ends every
 * session of the account, the one it came from included, without locking the sign-in.
 *
 * <p>Driven over HTTP with real sessions, because the claim is about what the <em>next</em> request
 * sees: the fifth refusal is still MSG08 as a 400 (D-35), and it is the request after it that is
 * answered 401.
 *
 * <p>Rule: SRS 3.2.5, UC-07; BR-03 (the threshold, and that its lockout is untouched); A-30; D-33;
 * messages MSG08, MSG14, MSG44.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WrongCurrentPasswordTest {

    private static final String TEST_DOMAIN = "@t014guess.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final String NEW_PASSWORD = "Zyxwvu9Q";

    private static final String PROFILE = "/api/v1/me/profile";

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

    /**
     * The threshold: five wrong answers, each MSG08; then the guessing session is refused, and so is
     * every other session of the account. The owner signs in again with the right password — the sign-in
     * was never locked.
     */
    @Test
    void A30_theFifthWrongCurrentPassword_endsEverySessionAndLeavesSignInOpen() throws Exception {
        registerVerified("guessed");
        String guessing = signIn("guessed");
        String elsewhere = signIn("guessed");

        for (int attempt = 0; attempt < 5; attempt++) {
            wrongCurrentPassword(guessing).andExpect(status().isBadRequest());
        }

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, guessing))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"));
        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, elsewhere)).andExpect(status().isUnauthorized());
        String again = signIn("guessed");
        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, again)).andExpect(status().isOk());
        assertThat(column("guessed", "failed_login_count")).isZero();
        assertThat(column("guessed", "failed_password_change_count"))
                .as("the count started again")
                .isZero();
    }

    /** Below the threshold nothing ends: four wrong answers leave the session working and are counted. */
    @Test
    void A30_fourWrongCurrentPasswords_leaveTheSessionWorking() throws Exception {
        registerVerified("four");
        String session = signIn("four");

        for (int attempt = 0; attempt < 4; attempt++) {
            wrongCurrentPassword(session);
        }

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, session)).andExpect(status().isOk());
        assertThat(column("four", "failed_password_change_count"))
                .as("each refusal committed its count although the request failed")
                .isEqualTo(4);
    }

    /**
     * A successful change resets the count, so four wrong answers before it and four after it are two
     * separate runs, and neither reaches five.
     */
    @Test
    void A30_aSuccessfulChange_resetsTheCount() throws Exception {
        registerVerified("recovers");
        String session = signIn("recovers");
        for (int attempt = 0; attempt < 4; attempt++) {
            wrongCurrentPassword(session);
        }

        mvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk());
        assertThat(column("recovers", "failed_password_change_count")).isZero();

        for (int attempt = 0; attempt < 4; attempt++) {
            wrongCurrentPassword(session);
        }
        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, session)).andExpect(status().isOk());
    }

    private ResultActions wrongCurrentPassword(String bearer) throws Exception {
        return mvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("Wrong1234", NEW_PASSWORD)))
                .andExpect(jsonPath("$.messageCode").value("MSG08"));
    }

    private static String changeBody(String current, String next) {
        return "{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\", \"confirmPassword\": \""
                + next + "\"}";
    }

    private int column(String localPart, String column) {
        return sql.sql("select " + column + " from user_account where email = ?")
                .param(localPart + TEST_DOMAIN)
                .query(Integer.class)
                .single();
    }

    private void registerVerified(String localPart) throws Exception {
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
    }

    private String signIn(String localPart) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\": \"" + localPart + TEST_DOMAIN + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }
}

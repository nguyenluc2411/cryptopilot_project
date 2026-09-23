package com.cryptopilot.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Profile and Notifications tabs of SCR-07 over HTTP (SRS UC-06, UC-08, section 3.2.5): what is
 * shown, what a save writes, the message each answer names, and the ranges each field is held to.
 *
 * <p>Every request carries an access token obtained by signing in through the real endpoint, so the
 * account a request acts on is the one the token was issued to — which is the only way these
 * endpoints can name an account at all.
 *
 * <p>The ranges of SRS 3.2.5 are asserted on both sides of each boundary: BR-30's "capital greater
 * than 0" at zero and just above it, and "risk % from 0.1 to 10" at each end and just outside it.
 *
 * <p>Rule: BR-30; SRS UC-06, UC-08, sections 3.2.5 and 4.2.4; messages MSG01, MSG14, MSG15.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ProfileControllerTest {

    private static final String TEST_DOMAIN = "@t014profile.invalid";

    private static final String PASSWORD = "Abcdefg1";

    private static final String PROFILE = "/api/v1/me/profile";

    private static final String PREFERENCES = "/api/v1/me/notification-preferences";

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
     * A new profile, as registration left it: the display name given, no trading defaults and both
     * channels on (SRS UC-01, "a USER_PROFILE with default values").
     */
    @Test
    void UC06_aNewProfile_showsTheRegistrationDefaults() throws Exception {
        String token = signedInTrader("fresh");

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fresh" + TEST_DOMAIN))
                .andExpect(jsonPath("$.displayName").value("Test Person"))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.defaultCapital").doesNotExist())
                .andExpect(jsonPath("$.tradingStyle").doesNotExist())
                .andExpect(jsonPath("$.notifyEmail").value(true))
                .andExpect(jsonPath("$.notifyPush").value(true));
    }

    /**
     * SRS 3.2.5: a save answers MSG14 and what was saved is what the tab shows next. The figures reach
     * the client as strings at the scale of their columns, so nothing is lost in JavaScript
     * (TECHNICAL_DESIGN 5.4).
     */
    @Test
    void UC06_aValidSave_answersMsg14AndIsShownBack() throws Exception {
        String token = signedInTrader("saves");

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("New Name", "1500.25", "1.5", "\"SWING\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.defaultCapital").value("1500.25000000"))
                .andExpect(jsonPath("$.defaultRiskPercent").value("1.500"))
                .andExpect(jsonPath("$.tradingStyle").value("SWING"));
        assertThat(storedCapitalOf("saves")).isEqualByComparingTo("1500.25");
    }

    /**
     * The tab sends every field, so a default left empty clears the stored one: a trader who removes
     * their default capital gets an empty plan form, not the old figure.
     */
    @Test
    void UC06_anEmptyDefault_clearsTheStoredOne() throws Exception {
        String token = signedInTrader("clears");
        mvc.perform(put(PROFILE)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileBody("Someone", "1000", "2", "\"DAY\"")));

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Someone", null, null, null)))
                .andExpect(status().isOk());

        assertThat(storedCapitalOf("clears")).isNull();
    }

    /** SRS 3.2.5: the display name is 2 to 50 characters; outside that is MSG01, as at registration. */
    @ParameterizedTest(name = "a display name of {0} characters is refused")
    @ValueSource(ints = {0, 1, 51})
    void UC06_aDisplayNameOutsideTwoToFifty_answersMsg01(int length) throws Exception {
        String token = signedInTrader("name" + length);

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("x".repeat(length), null, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.displayName").value("MSG01"));
    }

    /** And the two ends of the range are inside it. */
    @ParameterizedTest(name = "a display name of {0} characters is accepted")
    @ValueSource(ints = {2, 50})
    void UC06_aDisplayNameAtEitherEnd_isAccepted(int length) throws Exception {
        String token = signedInTrader("edge" + length);

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("x".repeat(length), null, null, null)))
                .andExpect(status().isOk());
    }

    /** BR-30: capital greater than 0. Zero and below are MSG15; so are more decimals than are stored. */
    @ParameterizedTest(name = "a default capital of {0} is refused")
    @ValueSource(strings = {"0", "-1", "0.000000001"})
    void BR30_aDefaultCapitalThatIsNotPositive_answersMsg15(String capital) throws Exception {
        String token = signedInTrader("capital-" + capital.replace('.', '_').replace('-', 'm'));

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Someone", capital, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultCapital").value("MSG15"));
    }

    /** BR-30: the smallest capital the column can hold is greater than zero and is accepted. */
    @Test
    void BR30_theSmallestPositiveCapital_isAccepted() throws Exception {
        String token = signedInTrader("tiny");

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Someone", "0.00000001", null, null)))
                .andExpect(status().isOk());
    }

    /** BR-30: risk % from 0.1 to 10 — just outside either end is MSG15. */
    @ParameterizedTest(name = "a default risk of {0}% is refused")
    @ValueSource(strings = {"0.099", "0", "10.001", "11"})
    void BR30_aDefaultRiskOutsideTheRange_answersMsg15(String risk) throws Exception {
        String token = signedInTrader("risk-out-" + risk.replace('.', '_'));

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Someone", null, risk, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultRiskPercent").value("MSG15"));
    }

    /** BR-30: and both ends of the range are inside it. */
    @ParameterizedTest(name = "a default risk of {0}% is accepted")
    @ValueSource(strings = {"0.1", "10"})
    void BR30_aDefaultRiskAtEitherEnd_isAccepted(String risk) throws Exception {
        String token = signedInTrader("risk-in-" + risk.replace('.', '_'));

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Someone", null, risk, null)))
                .andExpect(status().isOk());
    }

    /** A trading style that is not one of the four SRS 3.2.5 names is refused, and nothing is saved. */
    @Test
    void UC06_anUnknownTradingStyle_isRefused() throws Exception {
        String token = signedInTrader("style");

        mvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Changed", null, null, "\"HODL\"")))
                .andExpect(status().isBadRequest());

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.displayName").value("Test Person"));
    }

    /**
     * UC-08: the two switches are saved with MSG14 and shown back. Turning both off leaves the account
     * with in-app notifications only, which SRS 3.2.5 says are always on and therefore have no switch.
     */
    @Test
    void UC08_theNotificationSwitches_areSavedWithMsg14() throws Exception {
        String token = signedInTrader("quiet");

        mvc.perform(put(PREFERENCES)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": false, \"push\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.notifyEmail").value(false))
                .andExpect(jsonPath("$.notifyPush").value(true));
    }

    /** A body that forgot a switch is refused with MSG01 rather than read as "off". */
    @Test
    void UC08_aMissingSwitch_answersMsg01AndTurnsNothingOff() throws Exception {
        String token = signedInTrader("forgot");

        mvc.perform(put(PREFERENCES)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.push").value("MSG01"));

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.notifyEmail").value(true));
    }

    /**
     * SRS 4.2.4, ownership: a save reaches the caller's own profile and no other. There is no way to
     * name another one, and this proves the one that exists is not written by somebody else's request.
     */
    @Test
    void UC06_aSave_changesOnlyTheCallersProfile() throws Exception {
        String mine = signedInTrader("mine");
        String theirs = signedInTrader("theirs");

        mvc.perform(put(PROFILE)
                .header(HttpHeaders.AUTHORIZATION, mine)
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileBody("Mine Only", null, null, null)));

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, theirs))
                .andExpect(jsonPath("$.displayName").value("Test Person"));
    }

    /**
     * An access token that outlived its account — the row was removed after it was issued — reads
     * nothing. Its session went with the account's tokens, so the token itself is refused with 401 and
     * MSG44 before the profile is ever looked up (D-33), and never reaches a server error.
     */
    @Test
    void UC06_aTokenWhoseAccountIsGone_isRefused() throws Exception {
        String token = signedInTrader("gone");
        sql.sql("delete from user_account where email = ?")
                .param("gone" + TEST_DOMAIN)
                .update();

        mvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"));
    }

    private static String profileBody(String displayName, String capital, String risk, String style) {
        return "{\"displayName\": \"" + displayName + "\", \"defaultCapital\": " + capital
                + ", \"defaultRiskPercent\": " + risk + ", \"tradingStyle\": " + style + "}";
    }

    private BigDecimal storedCapitalOf(String localPart) {
        return sql.sql("select p.default_capital from user_profile p join user_account a on a.user_id = p.user_id"
                        + " where a.email = ?")
                .param(localPart + TEST_DOMAIN)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    /** Registers, verifies and signs in a Trader, and answers the {@code Authorization} header value. */
    private String signedInTrader(String localPart) throws Exception {
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
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }
}

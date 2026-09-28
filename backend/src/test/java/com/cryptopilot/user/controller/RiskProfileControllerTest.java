package com.cryptopilot.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.RiskProfile;
import com.cryptopilot.user.RiskProfileApi;
import com.cryptopilot.user.RiskProfileParameters;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
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
 * The risk profile and risk questionnaire of SCR-07 over HTTP, and the same profile read by another module through
 * {@link RiskProfileApi}: the BALANCED start, the suggestion that saves nothing, the choice, and MSG48 before AGGRESSIVE.
 *
 * <p>Rule: BR-66; SRS UC-06, section 3.2.5; messages MSG01, MSG14, MSG48; D-53.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RiskProfileControllerTest {

    private static final String TEST_DOMAIN = "@t103risk.invalid";
    private static final String PASSWORD = "Abcdefg1";
    private static final String RISK_PROFILE = "/api/v1/me/risk-profile";
    private static final String QUESTIONNAIRE = "/api/v1/me/risk-questionnaire";

    /** Every question answered with its middle option. */
    private static final String MIDDLE_ANSWERS = """
            {"answers": {
              "CAPACITY_SHARE_OF_SAVINGS": "A_FIFTH_TO_HALF",
              "CAPACITY_EMERGENCY_FUND": "3_TO_6_MONTHS",
              "CAPACITY_NEED_WITHIN_A_YEAR": "MAYBE",
              "ATTITUDE_DROP_OF_20_PERCENT": "WAIT",
              "ATTITUDE_PREFERRED_OUTCOME": "MODERATE",
              "ATTITUDE_EXPERIENCE": "1_TO_3_YEARS"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private RiskProfileApi riskProfileApi;

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** BR-66: a new Trader starts on BALANCED, shown on the profile and with its parameters. */
    @Test
    void BR66_aNewTrader_isBalanced() throws Exception {
        String token = signedInTrader("fresh");

        mvc.perform(get("/api/v1/me/profile").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.riskProfile").value("BALANCED"));
        mvc.perform(get(RISK_PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskProfile").value("BALANCED"))
                .andExpect(jsonPath("$.riskPerTradePercent").value(1))
                .andExpect(jsonPath("$.maxFuturesLeverage").value(5))
                .andExpect(jsonPath("$.maxTotalOpenRiskPercent").value(4));
    }

    @Test
    void BR66_theQuestionnaire_isSixQuestionsWithoutTheirPoints() throws Exception {
        String token = signedInTrader("reads");

        mvc.perform(get(QUESTIONNAIRE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(6))
                .andExpect(jsonPath("$.questions[0].group").value("CAPACITY"))
                .andExpect(jsonPath("$.questions[0].options.length()").value(3))
                .andExpect(jsonPath("$.questions[0].options[0].points").doesNotExist());
    }

    /** SRS 3.2.5: the questionnaire suggests a profile; the stored profile stays as it was. */
    @Test
    void BR66_aSuggestion_isReturnedAndNothingIsSaved() throws Exception {
        String token = signedInTrader("suggests");
        choose(token, "CONSERVATIVE", false).andExpect(status().isOk());

        mvc.perform(post(QUESTIONNAIRE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MIDDLE_ANSWERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityScore").value(6))
                .andExpect(jsonPath("$.attitudeScore").value(6))
                .andExpect(jsonPath("$.suggestedProfile").value("BALANCED"));

        assertThat(storedProfileOf("suggests")).isEqualTo("CONSERVATIVE");
    }

    @Test
    void BR66_anIncompleteQuestionnaire_isRefusedWithMsg01() throws Exception {
        String token = signedInTrader("incomplete");

        mvc.perform(post(QUESTIONNAIRE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\": {\"CAPACITY_EMERGENCY_FUND\": \"3_TO_6_MONTHS\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
        mvc.perform(post(QUESTIONNAIRE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    @Test
    void BR66_aLowerProfile_isSavedWithMsg14() throws Exception {
        String token = signedInTrader("lower");

        choose(token, "CONSERVATIVE", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG14"));

        mvc.perform(get(RISK_PROFILE).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.riskProfile").value("CONSERVATIVE"))
                .andExpect(jsonPath("$.riskPerTradePercent").value(0.5))
                .andExpect(jsonPath("$.maxFuturesLeverage").value(3));
    }

    /** SRS 3.2.5: switching to AGGRESSIVE unconfirmed answers MSG48 and changes nothing; confirmed, it is saved. */
    @Test
    void BR66_switchingToAggressive_needsTheConfirmationOfMsg48() throws Exception {
        String token = signedInTrader("aggressive");

        choose(token, "AGGRESSIVE", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG48"));
        assertThat(storedProfileOf("aggressive")).isEqualTo("BALANCED");

        choose(token, "AGGRESSIVE", true).andExpect(status().isOk());
        assertThat(storedProfileOf("aggressive")).isEqualTo("AGGRESSIVE");

        // Staying on AGGRESSIVE is not a switch.
        choose(token, "AGGRESSIVE", false).andExpect(status().isOk());
    }

    @Test
    void BR66_aChoiceWithoutAProfile_isRefusedWithMsg01() throws Exception {
        String token = signedInTrader("empty");

        mvc.perform(put(RISK_PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmAggressive\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    @Test
    void BR66_withoutASession_theEndpointsAreRefused() throws Exception {
        mvc.perform(get(RISK_PROFILE)).andExpect(status().isUnauthorized());
        mvc.perform(get(QUESTIONNAIRE)).andExpect(status().isUnauthorized());
    }

    /** Q-24: another module reads the chosen profile and its configured parameters through the module's API. */
    @Test
    void BR66_theModuleApi_readsTheChosenProfileAndItsParameters() throws Exception {
        String token = signedInTrader("api");
        choose(token, "AGGRESSIVE", true).andExpect(status().isOk());

        RiskProfileParameters parameters = riskProfileApi.parametersOf(accountIdOf("api"));

        assertThat(parameters.profile()).isEqualTo(RiskProfile.AGGRESSIVE);
        assertThat(parameters.riskPerTradePercent()).isEqualByComparingTo("2");
        assertThat(parameters.maxFuturesLeverage()).isEqualTo(10);
        assertThat(parameters.maxTotalOpenRiskPercent()).isEqualByComparingTo("6");
    }

    @Test
    void BR66_theModuleApi_readsBalancedForAnAccountWithoutAProfile() {
        assertThat(riskProfileApi.parametersOf(UUID.randomUUID()).profile()).isEqualTo(RiskProfile.BALANCED);
    }

    private ResultActions choose(String token, String profile, boolean confirm) throws Exception {
        return mvc.perform(put(RISK_PROFILE)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskProfile\": \"" + profile + "\", \"confirmAggressive\": " + confirm + "}"));
    }

    private String storedProfileOf(String localPart) {
        return sql.sql("select risk_profile from user_profile where user_id = ?")
                .param(accountIdOf(localPart))
                .query(String.class)
                .single();
    }

    private UUID accountIdOf(String localPart) {
        return sql.sql("select user_id from user_account where email = ?")
                .param(localPart + TEST_DOMAIN)
                .query(UUID.class)
                .single();
    }

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

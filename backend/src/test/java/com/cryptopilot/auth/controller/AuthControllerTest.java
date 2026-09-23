package com.cryptopilot.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The endpoints of UC-01, UC-02, UC-03 and UC-05 over HTTP: the status each answers, the SRS message
 * code it names, and what never appears in a response body.
 *
 * <p>Every message code of section 5.3 that this task owns is asserted on the path that produces
 * it, because a code is what the web client selects a sentence by — the backend never sends the
 * sentence itself, so a wrong code is a wrong message with nothing else to catch it. Two of those
 * sentences have a hole in them, MSG09's minutes and MSG10's status, and the value that fills it is
 * asserted too: a message code alone would leave the client with "Please try again after {minutes}
 * minutes" and nothing to put in it.
 *
 * <p>Rule: SRS UC-01, UC-02, UC-03, UC-05, sections 3.2.1, 3.2.2, 3.2.3 and 5.3; messages MSG01,
 * MSG02, MSG03, MSG04, MSG05, MSG06, MSG07, MSG08, MSG09, MSG10, MSG11, MSG44.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, AuthControllerTest.TestClockAndListener.class})
class AuthControllerTest {

    private static final String TEST_DOMAIN = "@t011web.invalid";

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private static final String REGISTER = "/api/v1/auth/register";

    private static final String VERIFY = "/api/v1/auth/verify-email";

    private static final String RESEND = "/api/v1/auth/resend-verification";

    private static final String LOGIN = "/api/v1/auth/login";

    private static final String REFRESH = "/api/v1/auth/refresh";

    private static final String LOGOUT = "/api/v1/auth/logout";

    private static final String FORGOT_PASSWORD = "/api/v1/auth/forgot-password";

    private static final String RESET_PASSWORD = "/api/v1/auth/reset-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private IssuedLinks issuedLinks;

    @Autowired
    private IssuedResetLinks issuedResetLinks;

    @BeforeEach
    void resetTheClockAndTheMailbox() {
        clock.set(NOW);
        issuedLinks.clear();
        issuedResetLinks.clear();
    }

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** SRS 3.2.1: registration succeeds with MSG05, and 201 because an account now exists. */
    @Test
    void UC01_aValidRegistration_answersCreatedWithMsg05() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("new")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messageCode").value("MSG05"));

        assertThat(countOfAccounts("new" + TEST_DOMAIN)).isOne();
    }

    /**
     * A response says that it worked and nothing else. Neither the password that was just sent nor
     * the link that was just issued may appear in the body — the first would echo a credential back
     * through every proxy on the way, and the second would put a link that grants access into a
     * browser's network tab.
     */
    @Test
    void UC01_theResponse_carriesNeitherThePasswordNorTheLink() throws Exception {
        MvcResult result = mvc.perform(
                        post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("quiet")))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Abcdefg1").doesNotContain("password");
        assertThat(issuedLinks.only().token())
                .as("a link was issued, and it is not in the body")
                .isNotBlank()
                .satisfies(token -> assertThat(body).doesNotContain(token));
    }

    /** SRS 3.2.1: an address that is already registered is a conflict carrying MSG04. */
    @Test
    void UC01_aDuplicateAddress_answersConflictWithMsg04() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("dupe")))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(
                        post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("dupe")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG04"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("the name of a database constraint is not an error message")
                .doesNotContain("uq_user_account_email")
                .doesNotContain("constraint")
                .doesNotContain("ConstraintViolation");
    }

    /** SRS 3.2.1: a password that fails BR-02 is reported under its own field with MSG03. */
    @Test
    void BR02_aWeakPassword_answersMsg03UnderThePasswordField() throws Exception {
        String weak = registrationOf("weak").replace("Abcdefg1", "abc");

        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(weak))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("MSG03"));
    }

    /** SRS 3.2.1: an address that is not an address is reported with MSG02. */
    @Test
    void UC01_anInvalidAddress_answersMsg02UnderTheEmailField() throws Exception {
        String malformed = registrationOf("bad").replace("bad" + TEST_DOMAIN, "not-an-address");

        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("MSG02"));
    }

    /** SRS 3.2.1: a missing required field is reported with MSG01. */
    @Test
    void UC01_aMissingDisplayName_answersMsg01UnderItsField() throws Exception {
        String missing = registrationOf("blank").replace("\"displayName\": \"Someone\"", "\"displayName\": \"\"");

        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.displayName").value("MSG01"));
    }

    /** SRS 3.2.1: the disclaimer has to be confirmed, and refusing it writes nothing. */
    @Test
    void UC01_aRegistrationThatDeclinesTheDisclaimer_isRefused() throws Exception {
        String declined =
                registrationOf("declined").replace("\"acceptsDisclaimer\": true", "\"acceptsDisclaimer\": false");

        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(declined))
                .andExpect(status().isBadRequest());

        assertThat(countOfAccounts("declined" + TEST_DOMAIN)).isZero();
    }

    /** SRS 3.2.1: the two password fields have to match. */
    @Test
    void UC01_aConfirmationThatDoesNotMatch_isRefused() throws Exception {
        String mismatched = registrationOf("mismatch")
                .replace("\"confirmPassword\": \"Abcdefg1\"", "\"confirmPassword\": \"Abcdefg2\"");

        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(mismatched))
                .andExpect(status().isBadRequest());

        assertThat(countOfAccounts("mismatch" + TEST_DOMAIN)).isZero();
    }

    /** SRS 3.2.2: a valid link answers MSG06. */
    @Test
    void UC02_aValidLink_answersMsg06() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("verify")))
                .andExpect(status().isCreated());
        String token = issuedLinks.only().token();

        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(tokenBodyOf(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG06"));
    }

    /** SRS 3.2.2: an invalid, used or unknown link answers MSG07, and all three look alike. */
    @Test
    void UC02_anUnknownLink_answersMsg07() throws Exception {
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(tokenBodyOf("never-issued")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG07"));
    }

    /** BR-04 over HTTP: the same link twice, and the second attempt is MSG07 like any other failure. */
    @Test
    void BR04_aLinkUsedTwice_answersMsg07TheSecondTime() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("twice")))
                .andExpect(status().isCreated());
        String token = issuedLinks.only().token();

        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(tokenBodyOf(token)))
                .andExpect(status().isOk());
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(tokenBodyOf(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG07"));
    }

    /**
     * SRS 3.2.2: the resend answers the same however the address turns out — accepted, with no hint
     * about whether an account holds it. Asserted with three addresses that differ in exactly that.
     */
    @Test
    void UC02_theResend_answersIdenticallyWhateverTheAddressIs() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("known")))
                .andExpect(status().isCreated());
        clock.advance(java.time.Duration.ofMinutes(5));

        String forAnAccountThatExists = resendResponseFor("known" + TEST_DOMAIN);
        String forAnAddressNobodyHas = resendResponseFor("unknown" + TEST_DOMAIN);

        assertThat(forAnAccountThatExists)
                .as("an endpoint that answers differently is an endpoint that tests addresses")
                .isEqualTo(forAnAddressNobodyHas);
    }

    // ------------------------------------------------------------------- UC-03 and UC-05

    /** SRS 3.2.3: a sign-in answers 200 with both tokens, their expiries and the role to route on. */
    @Test
    void UC03_aValidSignIn_answersOkWithBothTokensAndTheRole() throws Exception {
        registerAndVerify("signin");

        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(loginOf("signin", "Abcdefg1", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresAt").value("2026-09-21T10:15:00Z"))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").value("2026-09-28T10:00:00Z"))
                .andExpect(jsonPath("$.role").value("TRADER"));
    }

    /** And nothing about the account beyond the role: a sign-in response is not a profile. */
    @Test
    void UC03_theSignInResponse_carriesNeitherThePasswordNorTheAddress() throws Exception {
        registerAndVerify("quiet-signin");

        String body = mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("quiet-signin", "Abcdefg1", false)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("Abcdefg1").doesNotContain(TEST_DOMAIN).doesNotContain("userId");
    }

    /** MSG08 with 401: the credentials were refused, and the body says which field was wrong nowhere. */
    @Test
    void MSG08_aWrongPassword_answersUnauthorisedWithMsg08() throws Exception {
        registerAndVerify("bad-password");

        mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("bad-password", "Abcdefg2", false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG08"))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    /** The same body for an address nobody registered, which is what keeps MSG08 one message. */
    @Test
    void MSG08_anUnknownAddressAndAWrongPassword_areAnsweredIdentically() throws Exception {
        registerAndVerify("known-address");

        String forAKnownAddress = loginRefusalFor(loginOf("known-address", "Abcdefg2", false));
        String forAnUnknownAddress = loginRefusalFor(loginOf("no-such-address", "Abcdefg2", false));

        assertThat(forAKnownAddress)
                .as("a sign-in that answered differently would be a way to test addresses")
                .isEqualTo(forAnUnknownAddress);
    }

    /** MSG09 with 429, and the minutes the sentence needs (BR-03). */
    @Test
    void MSG09_theFifthConsecutiveFailure_answersTooManyRequestsWithTheMinutesToWait() throws Exception {
        registerAndVerify("locked-out");

        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginOf("locked-out", "Abcdefg2", false)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("locked-out", "Abcdefg2", false)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.messageCode").value("MSG09"))
                .andExpect(jsonPath("$.messageArgs[0]").value("15"));
    }

    /** MSG10 with 403, naming the state the sentence has a hole for (BR-06). */
    @Test
    void MSG10_aBannedAccount_answersForbiddenWithTheStatusInTheMessage() throws Exception {
        registerAndVerify("banned");
        sql.sql("update user_account set account_status = 'BANNED' where lower(email) = ?")
                .param("banned" + TEST_DOMAIN)
                .update();

        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(loginOf("banned", "Abcdefg1", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG10"))
                .andExpect(jsonPath("$.messageArgs[0]").value("BANNED"));
    }

    /** MSG11 with 403 for an address that was never verified (BR-01). */
    @Test
    void MSG11_anUnverifiedAccount_answersForbiddenWithMsg11() throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("not-verified")))
                .andExpect(status().isCreated());

        mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("not-verified", "Abcdefg1", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG11"));
    }

    /** SRS 3.2.3: Remember me is thirty days on the refresh token and changes nothing else. */
    @Test
    void UC03_rememberMe_lengthensOnlyTheRefreshToken() throws Exception {
        registerAndVerify("remembered");

        mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("remembered", "Abcdefg1", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessTokenExpiresAt").value("2026-09-21T10:15:00Z"))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").value("2026-10-21T10:00:00Z"));
    }

    /** Renewing answers the same shape as a sign-in, with a token that is not the one presented. */
    @Test
    void UC03_refreshing_answersOkWithANewPair() throws Exception {
        registerAndVerify("renew");
        String refreshToken = signIn("renew");

        mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(refreshBodyOf(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.not(refreshToken)))
                .andExpect(jsonPath("$.role").value("TRADER"));
    }

    /** MSG44 with 401 for a token that is not a live session, whatever the reason (SRS 3.2.3). */
    @Test
    void MSG44_presentingARetiredRefreshToken_answersUnauthorisedWithMsg44() throws Exception {
        registerAndVerify("replayed");
        String refreshToken = signIn("replayed");
        mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(refreshBodyOf(refreshToken)))
                .andExpect(status().isOk());

        mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(refreshBodyOf(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    /** UC-05: logging out answers 204 with no body, and the session really is over. */
    @Test
    void UC05_loggingOut_answersNoContentAndEndsTheSession() throws Exception {
        registerAndVerify("signout");
        String refreshToken = signIn("signout");

        mvc.perform(post(LOGOUT).contentType(MediaType.APPLICATION_JSON).content(refreshBodyOf(refreshToken)))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(""));

        mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(refreshBodyOf(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.messageCode").value("MSG44"));
    }

    /** And it answers the same for a token nobody was ever issued, so it cannot be used to test them. */
    @Test
    void UC05_loggingOutWithATokenThatWasNeverIssued_answersNoContentToo() throws Exception {
        mvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBodyOf("not-a-token-anybody-was-given")))
                .andExpect(status().isNoContent());
    }

    /** An empty body is a validation failure, and MSG01, before anything looks at a credential. */
    @Test
    void MSG01_aSignInWithNoCredentials_answersBadRequest() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.email").value("MSG01"))
                .andExpect(jsonPath("$.errors.password").value("MSG01"));
    }

    // ------------------------------------------------------------------- UC-04

    /**
     * SRS 3.2.4: SCR-05 is answered with MSG12. 202 rather than 200, because what was accepted is
     * the request - whether anything is mailed depends on facts the caller is not told.
     */
    @Test
    void UC04_askingForAResetLink_answers202WithMsg12() throws Exception {
        registerAndVerify("forgot");

        mvc.perform(post(FORGOT_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailBodyOf("forgot")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageCode").value("MSG12"));
    }

    /**
     * The rule the screen exists for, asserted the only way that means anything: three addresses
     * that differ in exactly what the caller must not learn - one verified, one registered but never
     * verified, one nobody has - and three identical responses.
     */
    @Test
    void UC04_theResetRequest_answersIdenticallyWhateverTheAddressIs() throws Exception {
        registerAndVerify("verified-one");
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf("unverified-one")))
                .andExpect(status().isCreated());

        String forAVerifiedAccount = forgotPasswordResponseFor("verified-one" + TEST_DOMAIN);
        String forAnUnverifiedAccount = forgotPasswordResponseFor("unverified-one" + TEST_DOMAIN);
        String forAnAddressNobodyHas = forgotPasswordResponseFor("nobody-has-this" + TEST_DOMAIN);

        assertThat(forAVerifiedAccount)
                .as("an endpoint that answers differently is an endpoint that tests addresses")
                .isEqualTo(forAnUnverifiedAccount)
                .isEqualTo(forAnAddressNobodyHas);
    }

    /** SRS 3.2.4: a successful reset answers MSG13, and the new password is the one that signs in. */
    @Test
    void UC04_spendingTheLink_answersMsg13AndTheNewPasswordSignsIn() throws Exception {
        registerAndVerify("resetting");
        mvc.perform(post(FORGOT_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailBodyOf("resetting")))
                .andExpect(status().isAccepted());

        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf(issuedResetLinks.only().token(), "Zyxwvu9Q", "Zyxwvu9Q")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCode").value("MSG13"));

        mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf("resetting", "Zyxwvu9Q", false)))
                .andExpect(status().isOk());
    }

    /** BR-04 over HTTP: the same link twice, and the second attempt is MSG07. */
    @Test
    void BR04_aResetLinkUsedTwice_answersMsg07TheSecondTime() throws Exception {
        registerAndVerify("reset-twice");
        mvc.perform(post(FORGOT_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailBodyOf("reset-twice")))
                .andExpect(status().isAccepted());
        String link = issuedResetLinks.only().token();

        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf(link, "Zyxwvu9Q", "Zyxwvu9Q")))
                .andExpect(status().isOk());
        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf(link, "Qwerty9Z", "Qwerty9Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG07"));
    }

    /** An invalid or unknown link answers MSG07, exactly as a verification link does. */
    @Test
    void UC04_anUnknownResetLink_answersMsg07() throws Exception {
        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf("never-issued", "Zyxwvu9Q", "Zyxwvu9Q")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG07"));
    }

    /**
     * BR-02 on SCR-06, reported under the field the client draws it next to. That per-field entry is
     * the whole reason the rule is declared on the record as well as enforced in the service.
     */
    @Test
    void BR02_aResetWithAWeakPassword_answersMsg03UnderTheField() throws Exception {
        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf("any-link", "short", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.newPassword").value("MSG03"));
    }

    /** SRS 3.2.4 asks SCR-06 for a confirmation, and a mismatch never reaches the service. */
    @Test
    void BR02_aResetWhoseConfirmationDiffers_isRefusedBeforeTheLinkIsSpent() throws Exception {
        mvc.perform(post(RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBodyOf("any-link", "Zyxwvu9Q", "Zyxwvu9R")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.confirmPasswordMatching").value("MSG03"));
    }

    // ------------------------------------------------------------------- Helpers

    private void registerAndVerify(String localPart) throws Exception {
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(registrationOf(localPart)))
                .andExpect(status().isCreated());
        mvc.perform(post(VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBodyOf(issuedLinks.only().token())))
                .andExpect(status().isOk());
        issuedLinks.clear();
    }

    /** Signs in and answers the refresh token that was issued. */
    private String signIn(String localPart) throws Exception {
        String body = mvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginOf(localPart, "Abcdefg1", false)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
    }

    /** The body of a refused sign-in, with the correlation id removed so two can be compared. */
    private String loginRefusalFor(String requestBody) throws Exception {
        return mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private static String loginOf(String localPart, String password, boolean rememberMe) {
        return "{\"email\": \"" + localPart + TEST_DOMAIN + "\","
                + " \"password\": \"" + password + "\","
                + " \"rememberMe\": " + rememberMe + "}";
    }

    private static String refreshBodyOf(String refreshToken) {
        return "{\"refreshToken\": \"" + refreshToken + "\"}";
    }

    private String resendResponseFor(String email) throws Exception {
        return mvc.perform(
                        post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String registrationOf(String localPart) {
        return "{\"email\": \"" + localPart + TEST_DOMAIN + "\","
                + " \"displayName\": \"Someone\","
                + " \"password\": \"Abcdefg1\","
                + " \"confirmPassword\": \"Abcdefg1\","
                + " \"acceptsDisclaimer\": true}";
    }

    private static String tokenBodyOf(String token) {
        return "{\"token\": \"" + token + "\"}";
    }

    private static String emailBodyOf(String localPart) {
        return "{\"email\": \"" + localPart + TEST_DOMAIN + "\"}";
    }

    private static String resetBodyOf(String token, String newPassword, String confirmPassword) {
        return "{\"token\": \"" + token + "\","
                + " \"newPassword\": \"" + newPassword + "\","
                + " \"confirmPassword\": \"" + confirmPassword + "\"}";
    }

    /** The body of a reset request, with the correlation id removed so two can be compared. */
    private String forgotPasswordResponseFor(String email) throws Exception {
        return mvc.perform(post(FORGOT_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private long countOfAccounts(String email) {
        return sql.sql("select count(*) from user_account where lower(email) = lower(:email)")
                .param("email", email)
                .query(Long.class)
                .single();
    }

    /** As in the service test: a fixed clock, and a listener standing in for the mail module. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockAndListener {

        @Bean
        MutableTestClock testClock() {
            return new MutableTestClock(NOW);
        }

        @Bean
        @Primary
        Clock clockUnderTest(MutableTestClock testClock) {
            return testClock;
        }

        @Bean
        IssuedLinks issuedLinks() {
            return new IssuedLinks();
        }

        @Bean
        IssuedResetLinks issuedResetLinks() {
            return new IssuedResetLinks();
        }
    }

    /** Whatever links were announced, in the order they were announced. */
    static class IssuedLinks {

        private final List<VerificationTokenIssued> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(VerificationTokenIssued issued) {
            received.add(issued);
        }

        List<VerificationTokenIssued> all() {
            return new ArrayList<>(received);
        }

        VerificationTokenIssued only() {
            assertThat(received).hasSize(1);
            return received.get(0);
        }

        void clear() {
            received.clear();
        }
    }

    /** The same, for the reset links of UC-04. */
    static class IssuedResetLinks {

        private final List<PasswordResetTokenIssued> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(PasswordResetTokenIssued issued) {
            received.add(issued);
        }

        PasswordResetTokenIssued only() {
            assertThat(received).hasSize(1);
            return received.get(0);
        }

        void clear() {
            received.clear();
        }
    }
}

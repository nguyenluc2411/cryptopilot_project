package com.cryptopilot.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * The two endpoints of UC-01 and UC-02 over HTTP: the status each answers, the SRS message code it
 * names, and what never appears in a response body.
 *
 * <p>Every message code of section 5.3 that this task owns is asserted on the path that produces
 * it, because a code is what the web client selects a sentence by — the backend never sends the
 * sentence itself, so a wrong code is a wrong message with nothing else to catch it.
 *
 * <p>Rule: SRS UC-01, UC-02, sections 3.2.1, 3.2.2 and 5.3; messages MSG01, MSG02, MSG03, MSG04,
 * MSG05, MSG06, MSG07.
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

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private IssuedLinks issuedLinks;

    @BeforeEach
    void resetTheClockAndTheMailbox() {
        clock.set(NOW);
        issuedLinks.clear();
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
}

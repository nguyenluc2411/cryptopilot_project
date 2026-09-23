package com.cryptopilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BR-02, clause by clause: a password has 8 to 64 characters and contains at least one upper-case
 * letter, one lower-case letter and one digit.
 *
 * <p>Every clause gets a password that fails only that clause, so a rule that is dropped fails one
 * case rather than disappearing into a pile of invalid inputs that were never going to pass
 * anyway. The lengths are tested at 7, 8, 64 and 65, because a bound is only specified at its
 * edges — a test at 20 characters would pass against {@code >= 8}, {@code > 5} and {@code >= 1}
 * alike.
 *
 * <p>This is the whole of BR-02 as a rule. That the same policy is also declared on the request
 * record, so a web client can draw the message under the field, is asserted where the endpoint is.
 */
class PasswordPolicyTest {

    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "Abcdefg1, the shortest password the rule allows",
        "Abcdefgh1, one over the lower bound",
        "aB3aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, the longest the rule allows",
        "P@ssw0rd!, punctuation is allowed, it is simply not required",
        "ÄbcdeF1ö, letters outside ASCII still count as letters"
    })
    void BR02_aPasswordSatisfyingEveryClause_isAccepted(String password, String why) {
        assertThatCode(() -> PasswordPolicy.requireCompliant(password)).as(why).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "Abcdef1, seven characters is one short",
        "ABCDEFG1, no lower-case letter",
        "abcdefg1, no upper-case letter",
        "Abcdefgh, no digit",
        "AB1, far too short and missing a class as well"
    })
    void BR02_aPasswordFailingAClause_isRefused(String password, String whichClause) {
        assertThatExceptionOfType(BusinessException.class)
                .as(whichClause)
                .isThrownBy(() -> PasswordPolicy.requireCompliant(password))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    @Test
    void BR02_aPasswordOfSixtyFiveCharacters_isOneTooMany() {
        String tooLong = "aB3" + "x".repeat(62);
        assertThat(tooLong).hasSize(65);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> PasswordPolicy.requireCompliant(tooLong))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    @Test
    void BR02_noPasswordAtAll_isRefusedRatherThanCrashing() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> PasswordPolicy.requireCompliant(null))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    /**
     * The rejection travels into logs, so it says which clause failed and never what was offered.
     * A message that echoed the candidate would put a password — very often a real one, mistyped
     * by one character — into every log aggregator the application ships to.
     */
    @ParameterizedTest
    @ValueSource(strings = {"hunter2", "correct horse battery staple"})
    void aRejection_namesTheRuleAndNeverThePasswordThatFailedIt(String secret) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> PasswordPolicy.requireCompliant(secret))
                .withMessageContaining("BR-02")
                .withMessageNotContaining(secret);
    }

    /**
     * The expression the request record declares has to accept and refuse exactly what the service
     * does. Two statements of one rule are two places for it to drift, so the drift is what is
     * asserted: the same inputs, the same verdicts.
     */
    @ParameterizedTest
    @CsvSource({"Abcdefg1, true", "Abcdef1, false", "ABCDEFG1, false", "abcdefg1, false", "Abcdefgh, false"})
    void BR02_theDeclaredPatternAndTheServiceRule_agree(String password, boolean acceptable) {
        boolean patternAccepts =
                Pattern.compile(PasswordPolicy.PATTERN).matcher(password).matches();
        boolean serviceAccepts = true;
        try {
            PasswordPolicy.requireCompliant(password);
        } catch (BusinessException refused) {
            serviceAccepts = false;
        }

        assertThat(patternAccepts).isEqualTo(acceptable);
        assertThat(serviceAccepts).isEqualTo(acceptable);
    }
}

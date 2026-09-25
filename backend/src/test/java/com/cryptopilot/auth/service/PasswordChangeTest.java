package com.cryptopilot.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.auth.model.IssuedSession;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * UC-07 end to end against the migrated schema: the Security tab of SCR-07 checks the current
 * password, applies BR-02 to the new one, and ends every session but the caller's own (SRS 3.2.5).
 *
 * <p>Not {@code @Transactional}, for the reason {@code PasswordResetTest} gives: what is asserted is
 * what commits — a hash that changed, sessions that another transaction opened and this one ended.
 * Each test deletes what it wrote.
 *
 * <p>The caller's session is read out of a real access token rather than invented, so these tests
 * also prove that the {@code sid} claim names the family of the refresh token issued beside it — the
 * fact the "other sessions" of SRS 3.2.5 depend on.
 *
 * <p>Rule: BR-02, BR-03 (what a wrong current password does not do); SRS UC-07, section 3.2.5;
 * messages MSG03, MSG08.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, PasswordChangeTest.TestClock.class})
class PasswordChangeTest {

    private static final String TEST_DOMAIN = "@t014pw.invalid";

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private static final String OLD_PASSWORD = "Abcdefg1";

    private static final String NEW_PASSWORD = "Zyxwvu9Q";

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordChangeService passwordChange;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private SecureTokenFactory tokenFactory;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void resetTheClock() {
        clock.set(NOW);
    }

    @AfterEach
    void removeWhatTheTestWrote() {
        sql.sql("delete from user_account where email like :pattern")
                .param("pattern", "%" + TEST_DOMAIN)
                .update();
    }

    /** The happy path: the new password is the one that signs in afterwards, the old one is not. */
    @Test
    void UC07_theRightCurrentPassword_replacesIt() {
        UUID userId = verifiedAccount("changes");
        IssuedSession session = signIn("changes");

        passwordChange.changePassword(userId, sessionOf(session), OLD_PASSWORD, NEW_PASSWORD);

        assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHashOf(userId)))
                .isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, passwordHashOf(userId)))
                .isFalse();
        assertThatCode(() -> signIn("changes", NEW_PASSWORD)).doesNotThrowAnyException();
    }

    /**
     * SRS 3.2.5: a wrong current password shows MSG08, and nothing is written — the hash is the one it
     * was, and no session was ended by a request that failed.
     */
    @Test
    void UC07_aWrongCurrentPassword_isRefusedWithMsg08AndChangesNothing() {
        UUID userId = verifiedAccount("mistyped");
        IssuedSession here = signIn("mistyped");
        IssuedSession elsewhere = signIn("mistyped");
        String hashBefore = passwordHashOf(userId);

        BusinessException refusal =
                refusalOf(() -> passwordChange.changePassword(userId, sessionOf(here), "Wrong1234", NEW_PASSWORD));

        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        assertThat(refusal.errorCode().messageCode()).isEqualTo("MSG08");
        assertThat(passwordHashOf(userId)).isEqualTo(hashBefore);
        assertThatCode(() -> authService.refresh(elsewhere.refreshToken())).doesNotThrowAnyException();
    }

    /**
     * BR-02 applies to the new password on this path too, and it is checked before the current one,
     * so a request that was never going to succeed is refused with MSG03 whatever the current password
     * was.
     */
    @Test
    void BR02_aWeakNewPassword_isRefusedWithMsg03() {
        UUID userId = verifiedAccount("weak");
        IssuedSession session = signIn("weak");

        BusinessException refusal =
                refusalOf(() -> passwordChange.changePassword(userId, sessionOf(session), OLD_PASSWORD, "short1A"));

        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
        assertThat(passwordEncoder.matches(OLD_PASSWORD, passwordHashOf(userId)))
                .isTrue();
    }

    /**
     * SRS 3.2.5: "on success all other sessions are revoked". Two other sessions, one of them
     * remembered, both end; the session the change was made from carries on and can still be renewed.
     */
    @Test
    void UC07_aChange_endsEveryOtherSessionAndKeepsTheCallers() {
        UUID userId = verifiedAccount("three-devices");
        IssuedSession laptop = signIn("three-devices");
        IssuedSession phone = signIn("three-devices");
        IssuedSession tablet = authService.login("three-devices" + TEST_DOMAIN, OLD_PASSWORD, true);

        passwordChange.changePassword(userId, sessionOf(laptop), OLD_PASSWORD, NEW_PASSWORD);

        assertThat(refusalOf(() -> authService.refresh(phone.refreshToken())).errorCode())
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
        assertThat(refusalOf(() -> authService.refresh(tablet.refreshToken())).errorCode())
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
        assertThatCode(() -> authService.refresh(laptop.refreshToken()))
                .as("the session the change was made from survives it")
                .doesNotThrowAnyException();
    }

    /**
     * A token issued before the {@code sid} claim existed names no session, so none can be kept: every
     * session ends, the stronger reading of the rule, and the caller signs in once more.
     */
    @Test
    void UC07_aTokenNamingNoSession_endsEverySession() {
        UUID userId = verifiedAccount("no-sid");
        IssuedSession session = signIn("no-sid");

        passwordChange.changePassword(userId, null, OLD_PASSWORD, NEW_PASSWORD);

        assertThat(refusalOf(() -> authService.refresh(session.refreshToken())).errorCode())
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** The change reaches only the caller's account: another account's sessions are untouched. */
    @Test
    void UC07_aChange_leavesAnotherAccountsSessionsAlone() {
        UUID mine = verifiedAccount("mine");
        verifiedAccount("theirs");
        IssuedSession myLaptop = signIn("mine");
        IssuedSession theirs = signIn("theirs");

        passwordChange.changePassword(mine, sessionOf(myLaptop), OLD_PASSWORD, NEW_PASSWORD);

        assertThatCode(() -> authService.refresh(theirs.refreshToken())).doesNotThrowAnyException();
    }

    /**
     * A wrong current password is not a failed sign-in, so it does not move BR-03's counter or lock the
     * sign-in (A-30, resolved). It is counted separately, and what the fifth does is proved in
     * {@code WrongCurrentPasswordTest}; here the sign-in simply still works afterwards.
     */
    @Test
    void BR03_aWrongCurrentPassword_doesNotCountTowardsTheLockout() {
        UUID userId = verifiedAccount("not-a-login");
        IssuedSession session = signIn("not-a-login");

        for (int attempt = 0; attempt < 5; attempt++) {
            refusalOf(() -> passwordChange.changePassword(userId, sessionOf(session), "Wrong1234", NEW_PASSWORD));
        }

        assertThat(failedLoginCountOf(userId)).isZero();
        assertThatCode(() -> signIn("not-a-login")).doesNotThrowAnyException();
    }

    /** An access token for an account that no longer exists changes nothing and says so with a 404. */
    @Test
    void UC07_anAccountThatNoLongerExists_isNotFound() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> passwordChange.changePassword(UUID.randomUUID(), null, OLD_PASSWORD, NEW_PASSWORD));
    }

    /**
     * The claim the other tests rely on: an access token names the family of the refresh token issued
     * beside it, and a renewed pair names the same family, because rotation keeps it.
     */
    @Test
    void UC07_theAccessToken_namesTheSessionItWasIssuedWithin() {
        verifiedAccount("sid");
        IssuedSession session = signIn("sid");
        UUID family = familyOf(session.refreshToken());

        IssuedSession renewed = authService.refresh(session.refreshToken());

        assertThat(sessionOf(session)).isEqualTo(family);
        assertThat(sessionOf(renewed)).isEqualTo(family);
    }

    private IssuedSession signIn(String localPart) {
        return signIn(localPart, OLD_PASSWORD);
    }

    private IssuedSession signIn(String localPart, String password) {
        return authService.login(localPart + TEST_DOMAIN, password, false);
    }

    private UUID sessionOf(IssuedSession session) {
        return UUID.fromString(jwtDecoder.decode(session.accessToken()).getClaimAsString(JwtConfig.SESSION_CLAIM));
    }

    private UUID familyOf(String refreshToken) {
        return sql.sql("select token_family_id from user_token where token_hash = ?")
                .param(tokenFactory.digestOf(refreshToken))
                .query(UUID.class)
                .single();
    }

    private static BusinessException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException refusal) {
            return refusal;
        }
        throw new AssertionError("the call was expected to be refused");
    }

    private UUID verifiedAccount(String localPart) {
        authService.register(localPart + TEST_DOMAIN, OLD_PASSWORD, "Test Person");
        UUID userId = sql.sql("select user_id from user_account where lower(email) = ?")
                .param(localPart.toLowerCase() + TEST_DOMAIN)
                .query(UUID.class)
                .single();
        sql.sql("update user_account set email_verified_at = ? where user_id = ?")
                .param(NOW.atOffset(ZoneOffset.UTC))
                .param(userId)
                .update();
        return userId;
    }

    private String passwordHashOf(UUID userId) {
        return sql.sql("select password_hash from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
    }

    private int failedLoginCountOf(UUID userId) {
        return sql.sql("select failed_login_count from user_account where user_id = ?")
                .param(userId)
                .query(Integer.class)
                .single();
    }

    @TestConfiguration
    static class TestClock {

        @Bean
        MutableTestClock testClock() {
            return new MutableTestClock(NOW);
        }

        @Bean
        @Primary
        Clock clockUnderTest(MutableTestClock testClock) {
            return testClock;
        }
    }
}

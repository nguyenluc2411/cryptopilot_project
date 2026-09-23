package com.cryptopilot.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.auth.config.TokenProperties;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * UC-03 and UC-05 end to end against the real database: what a sign-in writes, which message each
 * refusal carries, how long each token lives, and what rotation does to a family.
 *
 * <p>Nothing here is {@code @Transactional}, for the reason the registration tests next door give and
 * for one more that is specific to BR-03: the counter has to be written by a transaction that commits
 * while the request it belongs to is refused, and a test that rolled everything back could not tell
 * that apart from a counter that was never written. Each test commits and
 * {@link #removeWhatTheTestWrote()} deletes it again, because the suite shares one database and the
 * seed tests assert that exactly one account exists.
 *
 * <p>Every instant comes from a clock the test moves by hand, so an expiry is asserted exactly on its
 * boundary rather than approximately near it.
 *
 * <p>Rule: BR-01, BR-03, BR-06; SRS UC-03, UC-05, section 3.2.3; messages MSG08, MSG09, MSG10, MSG11,
 * MSG44; TECHNICAL_DESIGN sections 5.3 and 7.15.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, LoginAndSessionTest.TestClock.class})
class LoginAndSessionTest {

    /** Every address a test registers ends in this, which is how the cleanup finds them again. */
    private static final String TEST_DOMAIN = "@t012.invalid";

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private static final String PASSWORD = "Abcdefg1";

    private static final String WRONG_PASSWORD = "Abcdefg2";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserTokenRepository tokens;

    @Autowired
    private SecureTokenFactory refreshTokens;

    @Autowired
    private TokenProperties tokenProperties;

    @Autowired
    private JwtDecoder jwtDecoder;

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

    // ------------------------------------------------------------------- UC-03, the happy path

    /**
     * SRS 3.2.3: a sign-in issues both tokens, stores the refresh token hashed, and updates
     * {@code last_login_at}.
     */
    @Test
    void UC03_signingIn_issuesBothTokensAndRecordsTheLogin() {
        UUID userId = verifiedAccount("trader");

        IssuedSession session = authService.login("Trader" + TEST_DOMAIN, PASSWORD, false);

        assertThat(session.userId()).isEqualTo(userId);
        assertThat(session.role()).isEqualTo(UserRole.TRADER);
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(lastLoginOf(userId)).isEqualTo(NOW);
        assertThat(failedCountOf(userId)).isZero();
    }

    /** The address is matched without regard to case, as every other lookup of it is (BR-01). */
    @Test
    void UC03_theAddress_isMatchedWithoutRegardToCase() {
        verifiedAccount("mixedcase");

        assertThatCode(() -> authService.login("MIXEDCASE" + TEST_DOMAIN.toUpperCase(), PASSWORD, false))
                .doesNotThrowAnyException();
    }

    /**
     * The refresh token reaches the database only as a digest. Asserted by reading the column: the
     * value handed to the client must appear nowhere in {@code user_token}, and its digest must.
     */
    @Test
    void UC03_theRefreshToken_isStoredOnlyAsADigest() {
        UUID userId = verifiedAccount("hashed");

        IssuedSession session = authService.login("hashed" + TEST_DOMAIN, PASSWORD, false);

        String stored = sql.sql("select token_hash from user_token where user_id = ? and token_type = 'REFRESH'")
                .param(userId)
                .query(String.class)
                .single();
        assertThat(stored)
                .isNotEqualTo(session.refreshToken())
                .isEqualTo(refreshTokens.digestOf(session.refreshToken()))
                .matches("[0-9a-f]{64}");
    }

    /** A sign-in starts a family of its own, so logging out of one device leaves the others alone. */
    @Test
    void TD715_eachSignIn_startsItsOwnFamily() {
        verifiedAccount("families");

        IssuedSession first = authService.login("families" + TEST_DOMAIN, PASSWORD, false);
        IssuedSession second = authService.login("families" + TEST_DOMAIN, PASSWORD, false);

        assertThat(familyOf(first)).isNotEqualTo(familyOf(second));
    }

    // ------------------------------------------------------------------- UC-03, the refusals

    /**
     * MSG08 for a wrong password, revealing nothing about which field was wrong -- not in the code and
     * not in the sentence beside it. A refusal that said "the password for account 019b... is wrong"
     * would answer, in its detail, exactly the question the shared message code refuses to answer.
     */
    @Test
    void MSG08_aWrongPassword_isRefusedWithoutSayingWhichFieldWasWrong() {
        verifiedAccount("wrongpassword");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("wrongpassword" + TEST_DOMAIN, WRONG_PASSWORD, false))
                .satisfies(refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(refusal.getMessage())
                            .isEqualTo(com.cryptopilot.user.UserApi.WRONG_CREDENTIALS_DETAIL)
                            .doesNotContain(TEST_DOMAIN);
                });
    }

    /** A known and an unknown address are refused with the identical code and the identical sentence. */
    @Test
    void MSG08_aKnownAndAnUnknownAddress_areRefusedIdentically() {
        verifiedAccount("known-here");

        BusinessException known = refusalOf("known-here", WRONG_PASSWORD);
        BusinessException unknown = refusalOf("never-registered", WRONG_PASSWORD);

        assertThat(known.errorCode()).isEqualTo(unknown.errorCode());
        assertThat(known.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(known.messageArgs()).isEqualTo(unknown.messageArgs());
    }

    /** And the same code for an address nobody registered, which is what makes MSG08 one message. */
    @Test
    void MSG08_anAddressNobodyRegistered_isRefusedWithTheSameCode() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("nobody" + TEST_DOMAIN, PASSWORD, false))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    /**
     * MSG11 for an account whose address is not verified — BR-01, which T-011 could only prove as
     * "the flag is not set", now proved as "this account cannot sign in".
     */
    @Test
    void BR01_anUnverifiedAccount_cannotSignIn() {
        unverifiedAccount("unverified");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("unverified" + TEST_DOMAIN, PASSWORD, false))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    }

    /**
     * MSG11 is reached only by somebody who knew the password. An unverified account offered the wrong
     * one is answered MSG08, so the endpoint does not confirm that the address is registered.
     */
    @Test
    void BR01_anUnverifiedAccountWithTheWrongPassword_isAnsweredMsg08() {

        unverifiedAccount("unverified-wrong");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("unverified-wrong" + TEST_DOMAIN, WRONG_PASSWORD, false))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    /** MSG10 for a LOCKED or BANNED account (BR-06), naming which of the two it is. */
    @ParameterizedTest(name = "a {0} account is refused with MSG10")
    @ValueSource(strings = {"LOCKED", "BANNED"})
    void BR06_aLockedOrBannedAccount_cannotSignIn(String status) {
        UUID userId = verifiedAccount("status-" + status.toLowerCase());
        sql.sql("update user_account set account_status = ? where user_id = ?")
                .params(status, userId)
                .update();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("status-" + status.toLowerCase() + TEST_DOMAIN, PASSWORD, false))
                .satisfies(refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
                    assertThat(refusal.messageArgs())
                            .as("MSG10 names the state the account is in")
                            .containsExactly(status);
                });
    }

    // ------------------------------------------------------------------- BR-03 against the database

    /**
     * The counter has to survive the refusal that follows it. This is the test the whole
     * {@code REQUIRES_NEW} arrangement exists for: written in the transaction that raises MSG08, the
     * increment would roll back and the account could never be locked at all.
     */
    @Test
    void BR03_aRejectedAttempt_leavesTheCounterAdvancedAfterTheRefusal() {
        UUID userId = verifiedAccount("counts");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.login("counts" + TEST_DOMAIN, WRONG_PASSWORD, false));

        assertThat(failedCountOf(userId))
                .as("the write on the failure path committed although the request was refused")
                .isEqualTo(1);
    }

    @Test
    void BR03_fiveConsecutiveRejectedAttempts_lockTheAccountForFifteenMinutes() {
        UUID userId = verifiedAccount("lockout");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(refusalOf("lockout", WRONG_PASSWORD).errorCode())
                    .as("attempt %d is an ordinary wrong password", attempt)
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
        BusinessException fifth = refusalOf("lockout", WRONG_PASSWORD);

        assertThat(fifth.errorCode()).isEqualTo(ErrorCode.LOGIN_TEMPORARILY_LOCKED);
        assertThat(fifth.messageArgs()).containsExactly("15");
        assertThat(failedCountOf(userId)).isEqualTo(5);
        assertThat(lockedUntilOf(userId)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    /** And the right password does not open a locked-out account, which is what a lockout means. */
    @Test
    void BR03_theCorrectPasswordDuringTheLockout_isStillRefused() {
        verifiedAccount("locked-correct");
        lockOut("locked-correct");

        clock.advance(Duration.ofMinutes(5));
        BusinessException refusal = refusalOf("locked-correct", PASSWORD);

        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.LOGIN_TEMPORARILY_LOCKED);
        assertThat(refusal.messageArgs()).as("ten minutes still to wait").containsExactly("10");
    }

    /** Both sides of the fifteen-minute boundary, with the clock placed exactly on it. */
    @Test
    void BR03_theLockoutBoundary_isExclusiveAtItsEndInstant() {
        verifiedAccount("boundary");
        lockOut("boundary");

        clock.set(NOW.plus(Duration.ofMinutes(15)).minusMillis(1));
        assertThat(refusalOf("boundary", PASSWORD).errorCode())
                .as("one millisecond before the end, still locked out")
                .isEqualTo(ErrorCode.LOGIN_TEMPORARILY_LOCKED);

        clock.set(NOW.plus(Duration.ofMinutes(15)));
        assertThatCode(() -> authService.login("boundary" + TEST_DOMAIN, PASSWORD, false))
                .as("at the end instant itself, the sign-in succeeds")
                .doesNotThrowAnyException();
    }

    @Test
    void BR03_aSuccessfulSignIn_resetsTheCounterInTheDatabase() {
        UUID userId = verifiedAccount("reset");
        refusalOf("reset", WRONG_PASSWORD);
        refusalOf("reset", WRONG_PASSWORD);
        assertThat(failedCountOf(userId)).isEqualTo(2);

        authService.login("reset" + TEST_DOMAIN, PASSWORD, false);

        assertThat(failedCountOf(userId)).isZero();
        assertThat(lockedUntilOf(userId)).isNull();
    }

    // ------------------------------------------------------------------- the windows (SRS 3.2.3)

    /**
     * The three windows the SRS states, asserted as the values the application declares. A test that
     * only measured an expiry would keep passing with any number at all in the configuration.
     */
    @Test
    void SRS323_theTokenWindows_areTheDeclaredValues() {
        assertThat(tokenProperties.accessTokenTtl())
                .as("SRS 3.2.3: an access token valid for 15 minutes")
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(tokenProperties.refreshTokenTtl())
                .as("SRS 3.2.3: a refresh token valid for 7 days")
                .isEqualTo(Duration.ofDays(7));
        assertThat(tokenProperties.rememberMeRefreshTokenTtl())
                .as("SRS 3.2.3: 30 days with Remember me")
                .isEqualTo(Duration.ofDays(30));
    }

    /** And the declared values are the ones the issued tokens actually carry. */
    @ParameterizedTest(name = "rememberMe={0} gives a refresh token of {1} days")
    @CsvSource({"false,7", "true,30"})
    void SRS323_theIssuedTokens_expireWhenTheDeclaredWindowsSay(boolean rememberMe, int refreshDays) {
        verifiedAccount("windows-" + rememberMe);

        IssuedSession session = authService.login("windows-" + rememberMe + TEST_DOMAIN, PASSWORD, rememberMe);

        assertThat(session.accessTokenExpiresAt())
                .as("fifteen minutes either way: Remember me does not lengthen an access token")
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(session.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(refreshDays)));
        assertThat(jwtDecoder.decode(session.accessToken()).getExpiresAt())
                .as("and the token itself says so, not only the record beside it")
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    /** The claims the token carries, and the issuer and algorithm it is signed under. */
    @Test
    void RFC7519_theAccessToken_carriesTheSubjectTheRoleAndNothingAboutThePerson() {
        UUID userId = verifiedAccount("claims");

        Jwt token = jwtDecoder.decode(
                authService.login("claims" + TEST_DOMAIN, PASSWORD, false).accessToken());

        assertThat(token.getSubject()).isEqualTo(userId.toString());
        assertThat(token.getClaimAsString("role")).isEqualTo("TRADER");
        assertThat(token.getClaimAsString("iss")).isEqualTo(JwtConfig.ISSUER);
        assertThat(token.getHeaders()).containsEntry("alg", JwtConfig.ALGORITHM.getName());
        assertThat(token.getClaims())
                .as("nothing about the person: an access token is readable by whoever holds it")
                .doesNotContainKeys("email", "displayName");
    }

    /** The expiry is checked exactly, against the injected clock and with no tolerance. */
    @Test
    void RFC8725_anAccessToken_isRefusedFromItsExpiryInstantOnwards() {
        verifiedAccount("expiry");
        String accessToken =
                authService.login("expiry" + TEST_DOMAIN, PASSWORD, false).accessToken();

        clock.set(NOW.plus(Duration.ofMinutes(15)).minusMillis(1));
        assertThatCode(() -> jwtDecoder.decode(accessToken))
                .as("one millisecond before the expiry, still accepted")
                .doesNotThrowAnyException();

        clock.set(NOW.plus(Duration.ofMinutes(15)));
        assertThatExceptionOfType(JwtException.class)
                .as("at the expiry instant itself, refused -- no clock skew is granted")
                .isThrownBy(() -> jwtDecoder.decode(accessToken));
    }

    // ------------------------------------------------------------------- rotation (TD 7.15)

    @Test
    void TD715_refreshing_retiresThePresentedTokenAndIssuesASuccessorInTheSameFamily() {
        verifiedAccount("rotate");
        IssuedSession first = authService.login("rotate" + TEST_DOMAIN, PASSWORD, false);

        clock.advance(Duration.ofMinutes(1));
        IssuedSession second = authService.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(familyOf(second)).isEqualTo(familyOf(first));
        assertThat(usedAtOf(first.refreshToken()))
                .as("the token presented is retired the moment it is redeemed")
                .isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(usedAtOf(second.refreshToken())).isNull();
    }

    /** The successor is a working session: it can be rotated again. */
    @Test
    void TD715_theSuccessor_canItselfBeRotated() {
        verifiedAccount("rotate-twice");
        IssuedSession first = authService.login("rotate-twice" + TEST_DOMAIN, PASSWORD, false);

        IssuedSession second = authService.refresh(first.refreshToken());
        IssuedSession third = authService.refresh(second.refreshToken());

        assertThat(familyOf(third)).isEqualTo(familyOf(first));
        assertThat(usedAtOf(second.refreshToken())).isNotNull();
    }

    /** A retired token presented again ends the whole family, both the copy and the original. */
    @Test
    void TD715_presentingARetiredToken_revokesTheEntireFamily() {
        verifiedAccount("reuse");
        IssuedSession first = authService.login("reuse" + TEST_DOMAIN, PASSWORD, false);
        IssuedSession second = authService.refresh(first.refreshToken());

        assertThat(refusalOfRefresh(first.refreshToken()).errorCode())
                .as("the replayed token is answered MSG44")
                .isEqualTo(ErrorCode.SESSION_EXPIRED);

        assertThat(usedAtOf(second.refreshToken()))
                .as("the token the legitimate client is holding is revoked too, because we cannot tell them apart")
                .isNotNull();
        assertThat(refusalOfRefresh(second.refreshToken()).errorCode())
                .as("and it no longer works")
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** Revoking one family does not touch another sign-in of the same account. */
    @Test
    void TD715_revokingOneFamily_leavesTheOtherSessionsOfTheAccountAlone() {
        verifiedAccount("two-devices");
        IssuedSession phone = authService.login("two-devices" + TEST_DOMAIN, PASSWORD, false);
        IssuedSession laptop = authService.login("two-devices" + TEST_DOMAIN, PASSWORD, false);
        authService.refresh(phone.refreshToken());

        refusalOfRefresh(phone.refreshToken());

        assertThatCode(() -> authService.refresh(laptop.refreshToken()))
                .as("the other device was never part of this family")
                .doesNotThrowAnyException();
    }

    /** "Remember me" survives rotation although no column records it. */
    @ParameterizedTest(name = "a rememberMe={0} session keeps its {1}-day window across rotations")
    @CsvSource({"false,7", "true,30"})
    void TD715_rotationKeeps_theWindowTheSignInAskedFor(boolean rememberMe, int days) {
        verifiedAccount("remember-" + rememberMe);
        IssuedSession first = authService.login("remember-" + rememberMe + TEST_DOMAIN, PASSWORD, rememberMe);

        clock.advance(Duration.ofDays(1));
        IssuedSession second = authService.refresh(first.refreshToken());
        clock.advance(Duration.ofDays(1));
        IssuedSession third = authService.refresh(second.refreshToken());

        assertThat(third.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(2L + days)));
    }

    /**
     * The four ways a session can be over answer the identical detail as well as the identical code.
     * A problem detail carries a sentence, and four different sentences would tell whoever is holding
     * a copied token which of the four they hit -- which is what the shared code MSG44 exists to
     * refuse. The reason is in the log instead.
     */
    @Test
    void MSG44_everyWayASessionCanBeOver_answersTheIdenticalDetail() {
        verifiedAccount("indistinguishable");
        IssuedSession retired = authService.login("indistinguishable" + TEST_DOMAIN, PASSWORD, false);
        authService.refresh(retired.refreshToken());
        verifiedAccount("indistinguishable-2");
        IssuedSession expired = authService.login("indistinguishable-2" + TEST_DOMAIN, PASSWORD, false);

        String unknown = refusalOfRefresh(refreshTokens.newToken()).getMessage();
        String replayed = refusalOfRefresh(retired.refreshToken()).getMessage();
        clock.advance(Duration.ofDays(7));
        String stale = refusalOfRefresh(expired.refreshToken()).getMessage();

        assertThat(unknown).isEqualTo(AuthService.SESSION_OVER_DETAIL);
        assertThat(replayed).isEqualTo(unknown);
        assertThat(stale).isEqualTo(unknown);
    }

    @Test
    void MSG44_anUnknownRefreshToken_endsTheSession() {
        assertThat(refusalOfRefresh(refreshTokens.newToken()).errorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** An expired refresh token is refused, at its expiry instant exactly. */
    @Test
    void MSG44_anExpiredRefreshToken_isRefusedFromItsExpiryInstantOnwards() {
        verifiedAccount("refresh-expiry");
        IssuedSession session = authService.login("refresh-expiry" + TEST_DOMAIN, PASSWORD, false);

        clock.set(NOW.plus(Duration.ofDays(7)).minusMillis(1));
        assertThatCode(() -> authService.refresh(session.refreshToken()))
                .as("one millisecond before, still usable")
                .doesNotThrowAnyException();

        verifiedAccount("refresh-expiry-2");
        IssuedSession other = authService.login("refresh-expiry-2" + TEST_DOMAIN, PASSWORD, false);
        clock.advance(Duration.ofDays(7));
        assertThat(refusalOfRefresh(other.refreshToken()).errorCode())
                .as("at the expiry instant itself, refused")
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** An access token cannot be presented where a refresh token is expected, and the other way round. */
    @Test
    void MSG44_anAccessTokenPresentedAsARefreshToken_endsTheSession() {
        verifiedAccount("wrong-kind");
        IssuedSession session = authService.login("wrong-kind" + TEST_DOMAIN, PASSWORD, false);

        assertThat(refusalOfRefresh(session.accessToken()).errorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** A session whose account has since been banned is not renewed (BR-06). */
    @Test
    void BR06_refreshingASessionOfABannedAccount_isRefused() {
        UUID userId = verifiedAccount("banned-refresh");
        IssuedSession session = authService.login("banned-refresh" + TEST_DOMAIN, PASSWORD, false);
        sql.sql("update user_account set account_status = 'BANNED' where user_id = ?")
                .param(userId)
                .update();

        assertThat(refusalOfRefresh(session.refreshToken()).errorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    // ------------------------------------------------------------------- UC-05

    @Test
    void UC05_loggingOut_revokesTheTokenAndItsFamily() {
        verifiedAccount("logout");
        IssuedSession first = authService.login("logout" + TEST_DOMAIN, PASSWORD, false);
        IssuedSession second = authService.refresh(first.refreshToken());

        authService.logout(second.refreshToken(), null);

        assertThat(usedAtOf(second.refreshToken())).isEqualTo(NOW);
        assertThat(refusalOfRefresh(second.refreshToken()).errorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /** Logging out of one device leaves the others signed in, because each sign-in is its own family. */
    @Test
    void UC05_loggingOutOfOneDevice_leavesTheOthersSignedIn() {
        verifiedAccount("logout-one");
        IssuedSession phone = authService.login("logout-one" + TEST_DOMAIN, PASSWORD, false);
        IssuedSession laptop = authService.login("logout-one" + TEST_DOMAIN, PASSWORD, false);

        authService.logout(phone.refreshToken(), null);

        assertThatCode(() -> authService.refresh(laptop.refreshToken())).doesNotThrowAnyException();
    }

    /** It answers the same for a token that was never valid, so it cannot be used to test tokens. */
    @Test
    void UC05_loggingOutWithATokenThatWasNeverIssued_isAccepted() {
        assertThatCode(() -> authService.logout(refreshTokens.newToken(), null)).doesNotThrowAnyException();
    }

    /**
     * SRS 3.2.3 says logging out revokes the refresh token, and says nothing about the access token —
     * because nothing can. It is verified by its signature and its expiry and is never looked up, so
     * it keeps working for the rest of its fifteen minutes. Asserted rather than left implied, so that
     * the guarantee this endpoint does and does not give is written down somewhere executable.
     */
    @Test
    void UC05_theAccessToken_staysValidUntilItExpires() {
        verifiedAccount("logout-access");
        IssuedSession session = authService.login("logout-access" + TEST_DOMAIN, PASSWORD, false);

        authService.logout(session.refreshToken(), null);

        assertThatCode(() -> jwtDecoder.decode(session.accessToken()))
                .as("logging out cannot recall an access token already issued")
                .doesNotThrowAnyException();

        clock.set(NOW.plus(Duration.ofMinutes(15)));
        assertThatExceptionOfType(JwtException.class)
                .as("what ends it is its own expiry")
                .isThrownBy(() -> jwtDecoder.decode(session.accessToken()));
    }

    // ------------------------------------------------------------------- Helpers

    private UUID verifiedAccount(String localPart) {
        UUID userId = unverifiedAccount(localPart);
        sql.sql("update user_account set email_verified_at = ? where user_id = ?")
                .param(NOW.atOffset(java.time.ZoneOffset.UTC))
                .param(userId)
                .update();
        return userId;
    }

    private UUID unverifiedAccount(String localPart) {
        authService.register(localPart + TEST_DOMAIN, PASSWORD, "Test Person");
        return sql.sql("select user_id from user_account where lower(email) = ?")
                .param(localPart.toLowerCase() + TEST_DOMAIN)
                .query(UUID.class)
                .single();
    }

    private void lockOut(String localPart) {
        for (int i = 0; i < 5; i++) {
            refusalOf(localPart, WRONG_PASSWORD);
        }
    }

    private BusinessException refusalOf(String localPart, String password) {
        try {
            authService.login(localPart + TEST_DOMAIN, password, false);
            throw new AssertionError("the sign-in was expected to be refused");
        } catch (BusinessException refused) {
            return refused;
        }
    }

    private BusinessException refusalOfRefresh(String presentedToken) {
        try {
            authService.refresh(presentedToken);
            throw new AssertionError("the refresh was expected to be refused");
        } catch (BusinessException refused) {
            return refused;
        }
    }

    private UUID familyOf(IssuedSession session) {
        return sql.sql("select token_family_id from user_token where token_hash = ?")
                .param(refreshTokens.digestOf(session.refreshToken()))
                .query(UUID.class)
                .single();
    }

    private Instant usedAtOf(String refreshToken) {
        return sql.sql("select used_at from user_token where token_hash = ?")
                .param(refreshTokens.digestOf(refreshToken))
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private Instant lastLoginOf(UUID userId) {
        return columnOfAccount(userId, "last_login_at", Instant.class);
    }

    private Instant lockedUntilOf(UUID userId) {
        return columnOfAccount(userId, "locked_until", Instant.class);
    }

    private int failedCountOf(UUID userId) {
        return columnOfAccount(userId, "failed_login_count", Integer.class);
    }

    private <T> T columnOfAccount(UUID userId, String column, Class<T> type) {
        return sql.sql("select " + column + " from user_account where user_id = ?")
                .param(userId)
                .query(type)
                .optional()
                .orElse(null);
    }

    /** The clock the whole application reads, so every expiry is asserted at its boundary. */
    @TestConfiguration(proxyBeanMethods = false)
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

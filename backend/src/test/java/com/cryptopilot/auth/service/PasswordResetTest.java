package com.cryptopilot.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * UC-04 end to end against the migrated schema: what asking for a reset link does, what spending one
 * does, and what BR-04 requires of both.
 *
 * <p>Nothing here is {@code @Transactional}, for the reason the tests next door give: the rules are
 * about what <em>commits</em> — that a link exists to be spent, that spending it ends sessions that
 * were opened by an earlier transaction — and a test that rolled back could not observe any of it.
 * Each test commits and {@link #removeWhatTheTestWrote()} deletes it again, because the suite shares
 * one database and the seed tests assert that exactly one account exists.
 *
 * <p>Every instant comes from a clock the test moves by hand, so the thirty minutes of BR-04 are
 * asserted exactly on their boundary rather than approximately near it.
 *
 * <p>Rule: BR-02, BR-04; SRS UC-04, section 3.2.4; messages MSG03, MSG07, MSG12, MSG13.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, PasswordResetTest.TestClockAndMailbox.class})
class PasswordResetTest {

    /** Every address a test registers ends in this, which is how the cleanup finds them again. */
    private static final String TEST_DOMAIN = "@t013.invalid";

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private static final String OLD_PASSWORD = "Abcdefg1";

    private static final String NEW_PASSWORD = "Zyxwvu9Q";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserTokenRepository tokens;

    @Autowired
    private SecureTokenFactory tokenFactory;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private IssuedResetLinks issuedLinks;

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

    // ------------------------------------------------ SRS 3.2.4, requesting a link

    /** The happy path: a verified account is issued a link, and the link is announced for mailing. */
    @Test
    void UC04_requestingAReset_issuesALinkForAVerifiedAccount() {
        UUID userId = verifiedAccount("wants-a-reset");

        authService.requestPasswordReset("Wants-a-reset" + TEST_DOMAIN);

        PasswordResetTokenIssued issued = issuedLinks.only();
        assertThat(issued.userId()).isEqualTo(userId);
        assertThat(issued.token()).isNotBlank();
        assertThat(resetTokenCountOf(userId)).isOne();
    }

    /**
     * BR-04: the link is valid for thirty minutes, counted from the moment it was issued. Asserted
     * against the stored column rather than against the event, because the column is what decides.
     */
    @Test
    void BR04_theLink_expiresThirtyMinutesAfterItWasIssued() {
        UUID userId = verifiedAccount("thirty-minutes");

        authService.requestPasswordReset("thirty-minutes" + TEST_DOMAIN);

        assertThat(issuedLinks.only().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(expiryOfTheResetTokenOf(userId)).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    /**
     * SRS 3.2.4: the value is never stored. What the row holds is the digest of the link, so a
     * database that leaks yields nothing anyone can present.
     */
    @Test
    void BR04_theLink_reachesTheDatabaseOnlyAsADigest() {
        UUID userId = verifiedAccount("digest-only");

        authService.requestPasswordReset("digest-only" + TEST_DOMAIN);
        String rawValue = issuedLinks.only().token();

        String stored = sql.sql("select token_hash from user_token where user_id = ? and token_type = ?")
                .param(userId)
                .param(TokenType.PASSWORD_RESET.name())
                .query(String.class)
                .single();
        assertThat(stored).isNotEqualTo(rawValue).isEqualTo(tokenFactory.digestOf(rawValue));
    }

    /**
     * SRS 3.2.4: only a verified account gets a link. An address that was registered but never
     * confirmed has not been shown to belong to the person asking, so mailing a password-setting
     * link to it would let whoever registered it take the account later.
     */
    @Test
    void UC04_anUnverifiedAccount_isIssuedNoLink() {
        UUID userId = unverifiedAccount("never-verified");

        authService.requestPasswordReset("never-verified" + TEST_DOMAIN);

        assertThat(issuedLinks.all()).isEmpty();
        assertThat(resetTokenCountOf(userId)).isZero();
    }

    /**
     * No address is ever refused, whatever it turns out to be. The method is {@code void} precisely
     * so that no caller has a value it could leak, and a refusal would be a value: an exception for
     * an unknown address would answer the question MSG12 exists to refuse.
     *
     * <p>What this does <em>not</em> prove is that the three are indistinguishable, and the name says
     * so. Equality of the response body is asserted over HTTP by
     * {@code AuthControllerTest.UC04_theResetRequest_answersIdenticallyWhateverTheAddressIs}, and
     * equality of the response <em>time</em> is not asserted anywhere, because it is not true: a
     * verified address costs an update, an insert and an event that the other two do not. That is
     * A-29, and it is deliberately an open item rather than a silent gap.
     */
    @Test
    void UC04_noAddressIsEverRefused_whateverItTurnsOutToBe() {
        verifiedAccount("exists");

        assertThatCode(() -> authService.requestPasswordReset("exists" + TEST_DOMAIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> authService.requestPasswordReset("nobody-registered-this" + TEST_DOMAIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> authService.requestPasswordReset("also-not-a-user" + TEST_DOMAIN))
                .doesNotThrowAnyException();
    }

    /** The address is matched without regard to case, as every other lookup of it is (BR-01). */
    @Test
    void UC04_theAddress_isMatchedWithoutRegardToCase() {
        verifiedAccount("mixedcase");

        authService.requestPasswordReset("MIXEDCASE" + TEST_DOMAIN.toUpperCase());

        assertThat(issuedLinks.all()).hasSize(1);
    }

    /**
     * A second request stops the first link working. BR-04 makes each link single-use and says
     * nothing about a second request; without this, every request would leave another working link
     * behind in a mailbox, which is the failure SRS 3.2.2 names for verification links and which is
     * worse here, because presenting one of these sets a password.
     */
    @Test
    void BR04_asecondRequest_stopsThePreviousLinkWorking() {
        verifiedAccount("two-requests");
        authService.requestPasswordReset("two-requests" + TEST_DOMAIN);
        String first = issuedLinks.only().token();
        issuedLinks.clear();

        authService.requestPasswordReset("two-requests" + TEST_DOMAIN);
        String second = issuedLinks.only().token();

        assertThat(refusalOfReset(first).errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
        assertThatCode(() -> authService.resetPassword(second, NEW_PASSWORD)).doesNotThrowAnyException();
    }

    // ------------------------------------------------ SRS 3.2.4, spending the link

    /** The happy path: the link is spent and the new password is the one that now signs in. */
    @Test
    void UC04_spendingTheLink_replacesThePassword() {
        UUID userId = verifiedAccount("resets");
        authService.requestPasswordReset("resets" + TEST_DOMAIN);

        authService.resetPassword(issuedLinks.only().token(), NEW_PASSWORD);

        assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHashOf(userId)))
                .as("the new password verifies against the stored hash")
                .isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, passwordHashOf(userId)))
                .as("the old one no longer does")
                .isFalse();
        assertThatCode(() -> authService.login("resets" + TEST_DOMAIN, NEW_PASSWORD, false))
                .doesNotThrowAnyException();
    }

    /** BR-04: usable once. The second attempt with the same link is refused with MSG07. */
    @Test
    void BR04_theLink_cannotBeSpentTwice() {
        verifiedAccount("spends-twice");
        authService.requestPasswordReset("spends-twice" + TEST_DOMAIN);
        String link = issuedLinks.only().token();

        authService.resetPassword(link, NEW_PASSWORD);

        assertThat(refusalOfReset(link).errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
    }

    /**
     * BR-04's thirty minutes, the inside of the boundary. The last usable instant is the one before
     * the expiry, and it is asserted exactly there rather than somewhere comfortably inside.
     *
     * <p>The two halves of the window are two tests rather than one, because each has to start from
     * the same known instant: a single test would have to issue its second link from a clock the
     * first half had already moved, and would then be measuring thirty minutes from the wrong
     * place while still passing.
     */
    @Test
    void BR04_theLink_stillWorksOneInstantBeforeItExpires() {
        verifiedAccount("just-inside");
        authService.requestPasswordReset("just-inside" + TEST_DOMAIN);
        String link = issuedLinks.only().token();

        clock.advance(Duration.ofMinutes(30).minusNanos(1));

        assertThatCode(() -> authService.resetPassword(link, NEW_PASSWORD)).doesNotThrowAnyException();
    }

    /** And the outside: a link valid for thirty minutes is not valid at thirty minutes. */
    @Test
    void BR04_theLink_doesNotWorkAtTheExpiryItself() {
        verifiedAccount("just-outside");
        authService.requestPasswordReset("just-outside" + TEST_DOMAIN);
        String link = issuedLinks.only().token();

        clock.advance(Duration.ofMinutes(30));

        assertThat(refusalOfReset(link).errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
    }

    /**
     * BR-04's second sentence: a successful reset revokes <em>all</em> refresh tokens of the account,
     * not the family of one of them. Two sessions are opened before the reset, which is the case the
     * rule exists for — somebody resetting a password is frequently doing it because one of the live
     * sessions is not theirs — and neither may survive it.
     */
    @Test
    void BR04_aSuccessfulReset_endsEverySessionOfTheAccount() {
        verifiedAccount("two-devices");
        IssuedSession laptop = authService.login("two-devices" + TEST_DOMAIN, OLD_PASSWORD, false);
        IssuedSession phone = authService.login("two-devices" + TEST_DOMAIN, OLD_PASSWORD, true);
        assertThat(laptop.refreshToken()).isNotEqualTo(phone.refreshToken());

        authService.requestPasswordReset("two-devices" + TEST_DOMAIN);
        authService.resetPassword(issuedLinks.only().token(), NEW_PASSWORD);

        assertThat(refusalOfRefresh(laptop.refreshToken()).errorCode())
                .as("the session the reset was asked from is over")
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
        assertThat(refusalOfRefresh(phone.refreshToken()).errorCode())
                .as("and so is the one opened from another device, which is the point of the rule")
                .isEqualTo(ErrorCode.SESSION_EXPIRED);
    }

    /**
     * The link of one account cannot end the sessions of another. The bulk revocation is keyed by
     * the account the token names, and this is the test that would fail if it were ever written
     * against something else.
     */
    @Test
    void BR04_aReset_leavesAnotherAccountsSessionsAlone() {
        verifiedAccount("mine");
        verifiedAccount("theirs");
        IssuedSession theirs = authService.login("theirs" + TEST_DOMAIN, OLD_PASSWORD, false);

        authService.requestPasswordReset("mine" + TEST_DOMAIN);
        authService.resetPassword(issuedLinks.only().token(), NEW_PASSWORD);

        assertThatCode(() -> authService.refresh(theirs.refreshToken())).doesNotThrowAnyException();
    }

    /**
     * A verification link cannot set a password. The lookup is by digest <em>and</em> kind, which is
     * what keeps two tokens issued to the same person by the same generator from being interchangeable.
     */
    @Test
    void BR04_aTokenOfAnotherKind_cannotSetAPassword() {
        UUID userId = verifiedAccount("wrong-kind");
        String verificationValue = "a-verification-value";
        tokens.save(UserToken.issue(
                userId,
                TokenType.EMAIL_VERIFICATION,
                tokenFactory.digestOf(verificationValue),
                NOW.plus(Duration.ofHours(24))));

        assertThat(refusalOfReset(verificationValue).errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
    }

    /** A value nobody was ever issued is refused, and says nothing about whether it once existed. */
    @Test
    void UC04_anUnknownLink_isRefusedWithMsg07() {
        assertThat(refusalOfReset("a-value-that-was-never-issued").errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED);
        assertThat(refusalOfReset("a-value-that-was-never-issued").errorCode().messageCode())
                .isEqualTo("MSG07");
    }

    /**
     * BR-02 applies to the new password, and it is checked before the link is spent. A request that
     * was never going to succeed must leave the link usable, or a mistyped password would cost the
     * person their only way back in.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Abc1", "abcdefg1", "ABCDEFG1", "Abcdefgh"})
    void BR02_aNewPasswordThatBreaksThePolicy_isRefusedAndSpendsNoLink(String candidate) {
        verifiedAccount("weak-password");
        authService.requestPasswordReset("weak-password" + TEST_DOMAIN);
        String link = issuedLinks.only().token();

        BusinessException refusal = refusalOfReset(link, candidate);

        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
        assertThat(refusal.errorCode().messageCode()).isEqualTo("MSG03");
        assertThatCode(() -> authService.resetPassword(link, NEW_PASSWORD))
                .as("the link survives a password the policy refused")
                .doesNotThrowAnyException();
    }

    /** The password the person typed never appears in the refusal, which travels into logs. */
    @Test
    void BR02_theRefusal_doesNotEchoThePassword() {
        verifiedAccount("no-echo");
        authService.requestPasswordReset("no-echo" + TEST_DOMAIN);
        String link = issuedLinks.only().token();
        String secret = "hunter2";

        assertThat(refusalOfReset(link, secret).getMessage()).doesNotContain(secret);
    }

    /**
     * BR-03's lockout is not cleared by a reset. The rule says five consecutive failures and that a
     * successful <em>login</em> resets the counter; a reset is not a login, and clearing it here
     * would make the reset endpoint a way of shortening a lockout without knowing the password.
     *
     * <p>Recorded as the reading rather than as settled: BR-03 does not say what a reset does to the
     * counter, and the question is on the open list.
     */
    @Test
    void BR03_aReset_doesNotClearTheFailedAttemptLockout() {
        UUID userId = verifiedAccount("locked-out");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> authService.login("locked-out" + TEST_DOMAIN, "Wrongpass1", false));
        }

        authService.requestPasswordReset("locked-out" + TEST_DOMAIN);
        authService.resetPassword(issuedLinks.only().token(), NEW_PASSWORD);

        assertThat(lockedUntilOf(userId))
                .as("the lockout instant survives the reset")
                .isNotNull();
        assertThatExceptionOfType(BusinessException.class)
                .as("and the new password does not open the account until it has been served")
                .isThrownBy(() -> authService.login("locked-out" + TEST_DOMAIN, NEW_PASSWORD, false))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.LOGIN_TEMPORARILY_LOCKED));
    }

    // ------------------------------------------------------------------- helpers

    private BusinessException refusalOfReset(String link) {
        return refusalOfReset(link, NEW_PASSWORD);
    }

    private BusinessException refusalOfReset(String link, String newPassword) {
        try {
            authService.resetPassword(link, newPassword);
            throw new AssertionError("the reset was expected to be refused");
        } catch (BusinessException refused) {
            return refused;
        }
    }

    private BusinessException refusalOfRefresh(String refreshToken) {
        try {
            authService.refresh(refreshToken);
            throw new AssertionError("the refresh was expected to be refused");
        } catch (BusinessException refused) {
            return refused;
        }
    }

    private UUID unverifiedAccount(String localPart) {
        authService.register(localPart + TEST_DOMAIN, OLD_PASSWORD, "Test Person");
        return sql.sql("select user_id from user_account where lower(email) = ?")
                .param(localPart.toLowerCase() + TEST_DOMAIN)
                .query(UUID.class)
                .single();
    }

    private UUID verifiedAccount(String localPart) {
        UUID userId = unverifiedAccount(localPart);
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

    private Instant lockedUntilOf(UUID userId) {
        return sql.sql("select locked_until from user_account where user_id = ?")
                .param(userId)
                .query(Instant.class)
                .single();
    }

    private int resetTokenCountOf(UUID userId) {
        return sql.sql("select count(*) from user_token where user_id = ? and token_type = ?")
                .param(userId)
                .param(TokenType.PASSWORD_RESET.name())
                .query(Integer.class)
                .single();
    }

    private Instant expiryOfTheResetTokenOf(UUID userId) {
        return sql.sql("select expires_at from user_token where user_id = ? and token_type = ?")
                .param(userId)
                .param(TokenType.PASSWORD_RESET.name())
                .query(Instant.class)
                .single();
    }

    @TestConfiguration
    static class TestClockAndMailbox {

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
        IssuedResetLinks issuedResetLinks() {
            return new IssuedResetLinks();
        }
    }

    /**
     * Whatever reset links were announced, in the order they were announced.
     *
     * <p>It listens after commit, which is the point rather than a detail: the registry of ADR-010
     * delivers to a listener of exactly this kind, so a link observed here is one that a mail module
     * would really have been asked to send.
     */
    static class IssuedResetLinks {

        private final List<PasswordResetTokenIssued> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(PasswordResetTokenIssued issued) {
            received.add(issued);
        }

        List<PasswordResetTokenIssued> all() {
            return new ArrayList<>(received);
        }

        PasswordResetTokenIssued only() {
            List<PasswordResetTokenIssued> all = all();
            assertThat(all).as("exactly one reset link was announced").hasSize(1);
            return all.get(0);
        }

        void clear() {
            received.clear();
        }
    }
}

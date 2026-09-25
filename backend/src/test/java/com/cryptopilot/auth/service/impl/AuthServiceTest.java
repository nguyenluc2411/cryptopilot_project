package com.cryptopilot.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.auth.service.AuthService;
import com.cryptopilot.auth.service.SecureTokenFactory;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-01 and UC-02 end to end against the migrated schema: what registration writes, what a
 * verification link does, and what the resend of SRS 3.2.2 allows.
 *
 * <p>Nothing here is {@code @Transactional}. The rules under test are about what <em>commits</em> —
 * that an account and its profile arrive together or not at all, that a link works once, that the
 * event carrying the mail is published only for a write that happened — and a test that rolled back
 * would never observe any of them. Each test therefore commits and {@link #removeWhatTheTestWrote()}
 * deletes it again, because the suite shares one database and the seed tests assert that exactly one
 * account exists.
 *
 * <p>Every instant comes from a clock the test moves by hand, so an expiry is asserted exactly on
 * its boundary rather than approximately near it.
 *
 * <p>Rule: BR-01, BR-02, BR-04, BR-05; SRS UC-01, UC-02, sections 3.2.1 and 3.2.2; messages MSG03,
 * MSG04, MSG06, MSG07.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, AuthServiceTest.TestClockAndListener.class})
class AuthServiceTest {

    /** Every address a test registers ends in this, which is how the cleanup finds them again. */
    private static final String TEST_DOMAIN = "@t011.invalid";

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private static final String VALID_PASSWORD = "Abcdefg1";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserTokenRepository tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecureTokenFactory tokenFactory;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private TransactionTemplate transactions;

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

    // ------------------------------------------------------------------- UC-01

    /**
     * SRS 3.2.1: registration creates the account with role TRADER and status ACTIVE, and a profile
     * with default values. The profile is the half with no cascade behind it, so it is the half
     * most worth asserting.
     */
    @Test
    void UC01_registering_writesTheAccountAndItsProfile() {
        authService.register("Trader" + TEST_DOMAIN, VALID_PASSWORD, "Jane Trader");

        UUID userId = idOf("trader" + TEST_DOMAIN);
        assertThat(columnOfAccount(userId, "role", String.class)).isEqualTo("TRADER");
        assertThat(columnOfAccount(userId, "account_status", String.class)).isEqualTo("ACTIVE");
        assertThat(sql.sql("select email_verified_at from user_account where user_id = :id")
                        .param("id", userId)
                        .query(Instant.class)
                        .optional())
                .as("registration leaves the address unverified")
                .isEmpty();
        assertThat(sql.sql("select display_name from user_profile where user_id = :id")
                        .param("id", userId)
                        .query(String.class)
                        .single())
                .isEqualTo("Jane Trader");
    }

    /**
     * SRS 4.2.4 and TECHNICAL_DESIGN 1.3: the password is stored only as a salted hash, at cost 12.
     *
     * <p>Three assertions, because any two of them can pass while the rule is broken. The stored
     * string is read for the work factor bcrypt records inside it, so lowering the bean to cost 4
     * fails here rather than silently producing a weaker table. It verifies against the password
     * that produced it. And it does not verify against a near miss, which is what would show if
     * something truncated or lower-cased the value on its way in.
     */
    @Test
    void BR02_theStoredPassword_isBcryptAtCostTwelveAndVerifiesOnlyAgainstItself() {
        authService.register("hashed" + TEST_DOMAIN, VALID_PASSWORD, "Hashed");

        String stored = sql.sql("select password_hash from user_account where email = :email")
                .param("email", "hashed" + TEST_DOMAIN)
                .query(String.class)
                .single();

        assertThat(stored).as("bcrypt writes its cost factor into the hash").startsWith("$2a$12$");
        assertThat(stored).isNotEqualTo(VALID_PASSWORD);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, stored)).isTrue();
        assertThat(passwordEncoder.matches("abcdefg1", stored))
                .as("a near miss must not verify")
                .isFalse();
        assertThat(passwordEncoder.matches("Abcdefg", stored)).isFalse();
    }

    /**
     * SRS UC-01: an account is created <em>with</em> a profile. Forcing the profile insert to fail
     * — a display name one character past its column — must leave no account behind, or a visitor
     * would own an address they can never register again and no screen could show their name.
     *
     * <p>The failure lands after the account row has already been inserted, because registration
     * flushes it to have the address checked; this is precisely the rollback that matters.
     */
    @Test
    void UC01_aFailureAfterTheAccountInsert_leavesNeitherRow() {
        String email = "rollback" + TEST_DOMAIN;
        String tooLongForTheColumn = "x".repeat(51);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> authService.register(email, VALID_PASSWORD, tooLongForTheColumn));

        assertThat(countOfAccounts(email))
                .as("the account insert must not survive the failed profile insert")
                .isZero();
        assertThat(countOfProfilesFor(email)).isZero();
    }

    /** Registration is one unit of work: a caller that rolls back leaves no account and no token. */
    @Test
    void UC01_aRollbackOfTheCallersTransaction_leavesNoAccountAndNoToken() {
        String email = "outer" + TEST_DOMAIN;

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> transactions.executeWithoutResult(status -> {
                    authService.register(email, VALID_PASSWORD, "Outer");
                    throw new IllegalStateException("the caller fails after registering");
                }));

        assertThat(countOfAccounts(email)).isZero();
        assertThat(countOfTokensFor(email)).isZero();
    }

    /** SRS 3.2.1: an address that is already registered shows MSG04, whatever case it is written in. */
    @ParameterizedTest(name = "{0} is already taken")
    @CsvSource({"duplicate@t011.invalid", "DUPLICATE@t011.invalid", "DuPlIcAtE@t011.invalid"})
    void UC01_anAddressThatIsAlreadyRegistered_answersMsg04(String secondAttempt) {
        authService.register("duplicate" + TEST_DOMAIN, VALID_PASSWORD, "First");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.register(secondAttempt, VALID_PASSWORD, "Second"))
                .satisfies(refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
                    assertThat(refusal.errorCode().messageCode()).isEqualTo("MSG04");
                });

        assertThat(countOfAccounts("duplicate" + TEST_DOMAIN)).isOne();
    }

    /**
     * The race the existence check cannot win. Inserting the competing row inside the transaction
     * registration will run in is what a concurrent registration looks like from here: the check
     * has already passed and the unique index is the only thing left to refuse the row.
     *
     * <p>Without the flush registration performs, this refusal would arrive at commit — after the
     * service returned, outside the catch — and the loser would be told MSG43 instead of MSG04.
     */
    @Test
    void UC01_aDuplicateThatGetsPastTheExistenceCheck_stillAnswersMsg04() {
        String email = "race" + TEST_DOMAIN;

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertCompetingAccountDirectly(email);
                    authService.register(email, VALID_PASSWORD, "The loser of the race");
                }))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    /**
     * BR-02 at the service, which is the guarantee rather than the convenience. The request record
     * declares the same rule for the web client, but every future way of setting a password reaches
     * a service, so this is the enforcement that has to hold. One case per clause, plus the lower
     * boundary either side and a password that satisfies everything.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "Abcdef1, seven characters is one below the minimum, false",
        "Abcdefg1, eight characters is the minimum and is accepted, true",
        "abcdefgh1, no upper-case letter, false",
        "ABCDEFGH1, no lower-case letter, false",
        "Abcdefgh, no digit, false",
        "Ab1, far below the minimum, false"
    })
    void BR02_theServiceEnforcesEveryClauseOfThePasswordPolicy(String password, String why, boolean accepted) {
        String email = "policy-" + Math.abs(why.hashCode()) + TEST_DOMAIN;

        if (accepted) {
            assertThatCode(() -> authService.register(email, password, "Policy"))
                    .as(why)
                    .doesNotThrowAnyException();
            return;
        }
        assertThatExceptionOfType(BusinessException.class)
                .as(why)
                .isThrownBy(() -> authService.register(email, password, "Policy"))
                .satisfies(refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
                    assertThat(refusal.errorCode().messageCode()).isEqualTo("MSG03");
                    assertThat(refusal.getMessage())
                            .as("a refusal never echoes the credential it refused")
                            .doesNotContain(password);
                });
        assertThat(countOfAccounts(email))
                .as("a refused password writes nothing")
                .isZero();
    }

    /** BR-02's upper boundary: 64 characters is accepted, 65 is not. */
    @Test
    void BR02_theUpperLengthBoundary_acceptsSixtyFourAndRefusesSixtyFive() {
        String sixtyFour = "Aa1" + "b".repeat(61);
        assertThat(sixtyFour).hasSize(64);

        assertThatCode(() -> authService.register("max" + TEST_DOMAIN, sixtyFour, "Max"))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.register("over" + TEST_DOMAIN, sixtyFour + "c", "Over"));
    }

    // --------------------------------------------------- the link that is mailed

    /**
     * BR-01: the link is valid for 24 hours and the database holds only its digest. The value
     * itself exists in exactly one place — the event that carries the mail — and both halves are
     * asserted: the event has a value, and the stored row is demonstrably not it.
     */
    @Test
    void BR01_registering_issuesALinkValidForTwentyFourHoursAndStoresOnlyItsDigest() {
        authService.register("link" + TEST_DOMAIN, VALID_PASSWORD, "Link");

        VerificationTokenIssued issued = issuedLinks.only();
        assertThat(issued.email()).isEqualTo("link" + TEST_DOMAIN);
        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(AuthServiceImpl.VERIFICATION_TOKEN_LIFETIME)
                .as("BR-01 states the window, and it is declared rather than inferred")
                .isEqualTo(Duration.ofHours(24));

        String storedHash = sql.sql("select token_hash from user_token where user_id = :id")
                .param("id", issued.userId())
                .query(String.class)
                .single();
        assertThat(storedHash)
                .as("the value never reaches the database")
                .isNotEqualTo(issued.token())
                .matches("[0-9a-f]{64}");
        assertThat(sql.sql("select token_type from user_token where user_id = :id")
                        .param("id", issued.userId())
                        .query(String.class)
                        .single())
                .isEqualTo("EMAIL_VERIFICATION");
    }

    /** The event names something that happened, so a registration that rolled back announces nothing. */
    @Test
    void UC01_aRegistrationThatRollsBack_announcesNoLink() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> transactions.executeWithoutResult(status -> {
                    authService.register("silent" + TEST_DOMAIN, VALID_PASSWORD, "Silent");
                    throw new IllegalStateException("the caller fails after registering");
                }));

        assertThat(issuedLinks.all())
                .as("no mail for a registration that did not happen")
                .isEmpty();
    }

    /** The event's own rendering must not carry the link into a log line. */
    @Test
    void BR01_theIssuedEvent_doesNotPrintItsToken() {
        authService.register("quiet" + TEST_DOMAIN, VALID_PASSWORD, "Quiet");

        VerificationTokenIssued issued = issuedLinks.only();
        assertThat(issued.toString())
                .doesNotContain(issued.token())
                .contains(issued.userId().toString());
    }

    // ------------------------------------------------------------------- UC-02

    /** SRS 3.2.2: a valid, unused, unexpired link verifies the address and is spent doing it. */
    @Test
    void BR01_aValidLink_verifiesTheAddressAndIsSpent() {
        VerificationTokenIssued issued = registerAndTakeTheLink("verify" + TEST_DOMAIN);

        authService.verifyEmail(issued.token());

        assertThat(sql.sql("select email_verified_at from user_account where user_id = :id")
                        .param("id", issued.userId())
                        .query(Instant.class)
                        .single())
                .isEqualTo(NOW);
        assertThat(sql.sql("select used_at from user_token where user_id = :id")
                        .param("id", issued.userId())
                        .query(Instant.class)
                        .single())
                .as("BR-04: the link is spent by the use that succeeded")
                .isEqualTo(NOW);
    }

    /** BR-04: a link works once. The second attempt is refused and the account is untouched. */
    @Test
    void BR04_theSameLinkASecondTime_isRefusedWithMsg07() {
        VerificationTokenIssued issued = registerAndTakeTheLink("once" + TEST_DOMAIN);
        authService.verifyEmail(issued.token());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.verifyEmail(issued.token()))
                .satisfies(
                        refusal -> assertThat(refusal.errorCode().messageCode()).isEqualTo("MSG07"));

        assertThat(sql.sql("select email_verified_at from user_account where user_id = :id")
                        .param("id", issued.userId())
                        .query(Instant.class)
                        .single())
                .as("the address was verified exactly once, at the first attempt")
                .isEqualTo(NOW);
    }

    /**
     * BR-01's boundary, one millisecond either side. The window is exclusive at its end: a link
     * valid for 24 hours works at 24 hours minus a millisecond and not at 24 hours exactly. Testing
     * both sides is what makes the difference between {@code isBefore} and {@code !isAfter} visible.
     */
    @Test
    void BR01_theExpiryBoundary_isExclusiveAtTwentyFourHours() {
        VerificationTokenIssued stillValid = registerAndTakeTheLink("edge-inside" + TEST_DOMAIN);
        clock.set(stillValid.expiresAt().minusMillis(1));
        assertThatCode(() -> authService.verifyEmail(stillValid.token()))
                .as("one millisecond before expiry the link still works")
                .doesNotThrowAnyException();

        clock.set(NOW);
        VerificationTokenIssued expired = registerAndTakeTheLink("edge-outside" + TEST_DOMAIN);
        clock.set(expired.expiresAt());
        assertThatExceptionOfType(BusinessException.class)
                .as("at the expiry instant itself the link is already gone")
                .isThrownBy(() -> authService.verifyEmail(expired.token()))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED));
    }

    /** A value nobody was ever issued is refused, and says nothing about whether it once existed. */
    @Test
    void UC02_anUnknownLink_isRefusedWithMsg07() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.verifyEmail("a-value-that-was-never-issued"))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED));
    }

    /**
     * A token of another kind cannot verify an address. The lookup is by digest <em>and</em> kind,
     * which is what stops a password-reset link — issued to the same person by the same generator —
     * from being spent on verification instead.
     */
    @Test
    void BR04_aTokenOfAnotherKind_cannotVerifyAnAddress() {
        VerificationTokenIssued issued = registerAndTakeTheLink("wrong-kind" + TEST_DOMAIN);
        String resetValue = "a-password-reset-value";
        transactions.executeWithoutResult(status -> tokens.save(UserToken.issue(
                issued.userId(),
                TokenType.PASSWORD_RESET,
                tokenFactory.digestOf(resetValue),
                NOW.plus(Duration.ofMinutes(30)))));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.verifyEmail(resetValue))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID_OR_EXPIRED));
    }

    // ---------------------------------------------------------- SRS 3.2.2 resend

    /**
     * SRS 3.2.2: "a new token invalidates previous unused verification tokens". Without it every
     * resend would leave another working link behind in a mailbox.
     */
    @Test
    void UC02_aResend_issuesANewLinkAndStopsThePreviousOne() {
        VerificationTokenIssued first = registerAndTakeTheLink("resend" + TEST_DOMAIN);
        clock.advance(AuthServiceImpl.RESEND_MINIMUM_INTERVAL);

        authService.resendVerification("resend" + TEST_DOMAIN);

        VerificationTokenIssued second = issuedLinks.only();
        assertThat(second.token()).isNotEqualTo(first.token());

        assertThatExceptionOfType(BusinessException.class)
                .as("the superseded link must stop working")
                .isThrownBy(() -> authService.verifyEmail(first.token()));
        assertThatCode(() -> authService.verifyEmail(second.token()))
                .as("the newest link is the one that works")
                .doesNotThrowAnyException();
    }

    /** SRS 3.2.2: once per 60 seconds, asserted at the boundary and against the declared interval. */
    @Test
    void UC02_aResendWithinTheMinimumInterval_sendsNothing() {
        registerAndTakeTheLink("throttle" + TEST_DOMAIN);

        clock.advance(AuthServiceImpl.RESEND_MINIMUM_INTERVAL.minusMillis(1));
        authService.resendVerification("throttle" + TEST_DOMAIN);
        assertThat(issuedLinks.all())
                .as("one millisecond short of the interval, nothing is sent")
                .isEmpty();

        clock.advance(Duration.ofMillis(1));
        authService.resendVerification("throttle" + TEST_DOMAIN);
        assertThat(issuedLinks.all()).as("at the interval, a link is sent").hasSize(1);

        assertThat(AuthServiceImpl.RESEND_MINIMUM_INTERVAL)
                .as("SRS 3.2.2 states the interval")
                .isEqualTo(Duration.ofSeconds(60));
    }

    /** SRS 3.2.2: at most five a day per address, counted over the tokens the account already holds. */
    @Test
    void UC02_aSixthLinkInOneDay_isNotSent() {
        registerAndTakeTheLink("daily" + TEST_DOMAIN);
        int resendsLeftAfterRegistration = AuthServiceImpl.RESEND_MAXIMUM_PER_DAY - 1;

        for (int resend = 0; resend < resendsLeftAfterRegistration; resend++) {
            clock.advance(AuthServiceImpl.RESEND_MINIMUM_INTERVAL);
            authService.resendVerification("daily" + TEST_DOMAIN);
        }
        assertThat(issuedLinks.all())
                .as("the registration link plus four resends is the five the SRS allows")
                .hasSize(resendsLeftAfterRegistration);

        clock.advance(AuthServiceImpl.RESEND_MINIMUM_INTERVAL);
        authService.resendVerification("daily" + TEST_DOMAIN);

        assertThat(issuedLinks.all()).as("the sixth is refused").hasSize(resendsLeftAfterRegistration);
        assertThat(AuthServiceImpl.RESEND_MAXIMUM_PER_DAY)
                .as("SRS 3.2.2 states the daily cap")
                .isEqualTo(5);
    }

    /**
     * The resend endpoint must not become a way to test whether an address holds an account, so an
     * address nobody registered and an address that is already verified both do nothing, quietly.
     */
    @Test
    void UC02_aResendForAnUnknownOrVerifiedAddress_doesNothingAndSaysNothing() {
        assertThatCode(() -> authService.resendVerification("nobody" + TEST_DOMAIN))
                .doesNotThrowAnyException();
        assertThat(issuedLinks.all()).isEmpty();

        VerificationTokenIssued issued = registerAndTakeTheLink("already" + TEST_DOMAIN);
        authService.verifyEmail(issued.token());
        clock.advance(AuthServiceImpl.RESEND_MINIMUM_INTERVAL);

        assertThatCode(() -> authService.resendVerification("already" + TEST_DOMAIN))
                .doesNotThrowAnyException();
        assertThat(issuedLinks.all()).as("a verified address needs no link").isEmpty();
    }

    // -------------------------------------------------------------------- helpers

    /** Registers, and hands back the link with the mailbox emptied for the test's own assertions. */
    private VerificationTokenIssued registerAndTakeTheLink(String email) {
        authService.register(email, VALID_PASSWORD, "Someone");
        VerificationTokenIssued issued = issuedLinks.only();
        issuedLinks.clear();
        return issued;
    }

    private UUID idOf(String email) {
        return sql.sql("select user_id from user_account where lower(email) = :email")
                .param("email", email)
                .query(UUID.class)
                .single();
    }

    private <T> T columnOfAccount(UUID userId, String column, Class<T> type) {
        return sql.sql("select " + column + " from user_account where user_id = :id")
                .param("id", userId)
                .query(type)
                .single();
    }

    private long countOfAccounts(String email) {
        return sql.sql("select count(*) from user_account where lower(email) = lower(:email)")
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private long countOfProfilesFor(String email) {
        return sql.sql("select count(*) from user_profile p join user_account a on a.user_id = p.user_id"
                        + " where lower(a.email) = lower(:email)")
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private long countOfTokensFor(String email) {
        return sql.sql("select count(*) from user_token t join user_account a on a.user_id = t.user_id"
                        + " where lower(a.email) = lower(:email)")
                .param("email", email)
                .query(Long.class)
                .single();
    }

    /** What a competing registration leaves behind: a row the existence check never saw. */
    private void insertCompetingAccountDirectly(String email) {
        sql.sql("insert into user_account"
                        + " (user_id, email, password_hash, role, account_status, created_at, updated_at, version)"
                        + " values (:id, :email, 'x', 'TRADER', 'ACTIVE', :now, :now, 0)")
                .param("id", UUID.randomUUID())
                .param("email", email)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The clock the whole application reads, and the stand-in for the module that will send the
     * mail. No mail library, no SMTP client and no outbound call: the listener records what was
     * published, which is exactly what a notification module will later do with it.
     */
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

    /**
     * Whatever links were announced, in the order they were announced.
     *
     * <p>It listens after commit, which is the whole point rather than a detail: the registry of
     * ADR-010 delivers to a listener of exactly this kind, and a plain {@code @EventListener} would
     * receive the event inside the transaction and so would happily "send" mail for a registration
     * that then rolled back. Listening the way the real module will is what lets
     * {@link #UC01_aRegistrationThatRollsBack_announcesNoLink()} mean anything.
     */
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

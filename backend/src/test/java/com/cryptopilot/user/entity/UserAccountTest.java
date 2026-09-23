package com.cryptopilot.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The account lifecycle, exercised without a database because none of it needs one: the rules are
 * about the state of one row, and a rule that needs the rest of the table is deliberately not here.
 *
 * <p>The transition matrix is the centre of this class. Every combination of a status and an action
 * is named, so a transition that is added or removed later has to be stated here rather than
 * silently allowed — a state machine tested only along its happy path is a state machine with
 * unspecified edges.
 *
 * <p>What is proved and what is not. BR-05 is covered here only where it is about one account: that
 * a self-registered account is a TRADER, and that the account holds exactly one role. That only an
 * administrator may grant ADMIN is authorization (T-015), and that the last active administrator
 * cannot be demoted or banned (BR-58) needs a count of the other accounts, so it belongs to the user
 * administration task. BR-06 is covered for the half that is a property of the account — a LOCKED or
 * BANNED account cannot log in — while the other half, that the change revokes the sessions, is a
 * write to the token table of another module and is proved in {@code AuthServiceTest}. BR-01 is
 * covered only as the account's side of verification; the 24-hour window and the single-use link are
 * the token's, and live in {@code UserTokenTest}.
 *
 * <p>BR-03 is covered here in full, clause by clause, because every one of its clauses is about the
 * state of one account: the count, the fifth failure exactly, the fifteen minutes, both sides of the
 * boundary, and what a success does to the counter. What is <em>not</em> here is that the counter
 * survives the request that rejected the sign-in, which is a property of a transaction and is proved
 * against the database in {@code UserServiceTest}.
 */
class UserAccountTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:15:30Z");

    // ------------------------------------------------------------------------------------------
    // Registration and role (BR-05)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR05_aSelfRegisteredAccount_isAlwaysAnActiveTraderWithAnUnverifiedAddress() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");

        assertThat(account.getRole())
                .as("the factory takes no role, so registration cannot produce an administrator")
                .isEqualTo(Role.TRADER);
        assertThat(account.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getEmailVerifiedAt()).isNull();
        assertThat(account.isEmailVerified()).isFalse();
        assertThat(account.getLastLoginAt()).isNull();
        assertThat(account.getId()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void BR05_changingTheRole_replacesTheOneRoleTheAccountHolds(Role role) {
        UserAccount account = activeVerifiedAccount();

        account.changeRole(role);

        assertThat(account.getRole()).isEqualTo(role);
    }

    @Test
    void BR05_anAccountWithoutARole_isRefused() {
        UserAccount account = activeVerifiedAccount();

        assertThatThrownBy(() -> account.changeRole(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void registration_refusesAnAccountWithoutAnAddressOrWithoutACredential() {
        assertThatThrownBy(() -> UserAccount.register("  ", "hash")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAccount.register("trader@example.invalid", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // The transition matrix (BR-05, BR-06)
    // ------------------------------------------------------------------------------------------

    /**
     * Every legal transition of the lifecycle, stated one by one. {@code BANNED} appears only as a
     * destination, which is the whole of "a ban is final".
     */
    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({"ACTIVE, lock,   LOCKED", "ACTIVE, ban,    BANNED", "LOCKED, unlock, ACTIVE", "LOCKED, ban,    BANNED"})
    void BR05_aLegalTransition_movesTheAccountToTheExpectedStatus(
            AccountStatus from, String action, AccountStatus expected) {
        UserAccount account = accountIn(from);

        actionOf(action).accept(account);

        assertThat(account.getAccountStatus()).isEqualTo(expected);
    }

    /**
     * Every transition the lifecycle does not have. Each is refused with the same code, because
     * each is the same thing: an action that does not exist from the state the account is in.
     */
    @ParameterizedTest(name = "{0} --{1}--> refused")
    @CsvSource({"ACTIVE, unlock", "LOCKED, lock", "BANNED, lock", "BANNED, unlock", "BANNED, ban"})
    void BR05_anIllegalTransition_isRefusedAndLeavesTheStatusUnchanged(AccountStatus from, String action) {
        UserAccount account = accountIn(from);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> actionOf(action).accept(account))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID);
        assertThat(account.getAccountStatus()).isEqualTo(from);
    }

    // ------------------------------------------------------------------------------------------
    // Signing in (BR-01, BR-06)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR06_anActiveVerifiedAccount_recordsTheInstantItLoggedIn() {
        UserAccount account = activeVerifiedAccount();

        account.recordLogin(NOW);

        assertThat(account.getLastLoginAt()).isEqualTo(NOW);
    }

    /**
     * The account half of BR-06. That locking or banning also revokes every session is the other
     * half, and it is a write to the token table of another module (T-012).
     */
    @ParameterizedTest
    @CsvSource({"LOCKED", "BANNED"})
    void BR06_aLockedOrBannedAccount_cannotLogIn(AccountStatus status) {
        UserAccount account = accountIn(status);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> account.recordLogin(NOW))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
        assertThat(account.getLastLoginAt()).isNull();
    }

    /**
     * BR-01 from the account's side: an unverified address blocks the sign-in. The link that
     * carries the verification, its 24-hour window and its single use are the token's side of the
     * same rule.
     */
    @Test
    void BR01_anAccountThatHasNotVerifiedItsAddress_cannotLogIn() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> account.recordLogin(NOW))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    /**
     * A banned account that never verified its address is told it is banned, not told to check its
     * mailbox: the status is examined first, so nobody is offered a verification link that cannot
     * help them. The SRS names both messages and no order between them, so the order follows from
     * what each message offers.
     */
    @Test
    void BR06_aBannedUnverifiedAccount_isRefusedForItsStatusRatherThanItsAddress() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");
        account.ban();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> account.recordLogin(NOW))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
    }

    // ------------------------------------------------------------------------------------------
    // Verification (BR-01)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR01_verifyingTheAddress_recordsTheInstantAndUnblocksTheSignIn() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");

        account.verifyEmail(NOW);

        assertThat(account.getEmailVerifiedAt()).isEqualTo(NOW);
        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.getRole()).isEqualTo(Role.TRADER);
    }

    /** The link is usable once, so a second verification is a link that has been used before. */
    @Test
    void BR01_verifyingAnAddressThatIsAlreadyVerified_isRefused() {
        UserAccount account = activeVerifiedAccount();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> account.verifyEmail(NOW.plusSeconds(60)))
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED);
        assertThat(account.getEmailVerifiedAt())
                .as("the first verification stands")
                .isEqualTo(NOW);
    }

    // ------------------------------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------------------------------

    @Test
    void anAccountThatChanges_staysTheSameEntity() {
        UserAccount account = activeVerifiedAccount();
        UserAccount sameRow = account;

        account.lock();

        assertThat(account).isEqualTo(sameRow).hasSameHashCodeAs(sameRow);
        assertThat(account).isNotEqualTo(activeVerifiedAccount());
    }

    // ------------------------------------------------------------------------------------------
    // The failed-attempt lockout (BR-03)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR03_aNewAccount_hasCountedNoFailuresAndIsNotLockedOut() {
        UserAccount account = activeVerifiedAccount();

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.isLockedOutAt(NOW)).isFalse();
    }

    /**
     * The count rises by one per failure and the account stays usable until the fifth. Four is the
     * interesting number here: the clause says five, so four must not lock.
     */
    @ParameterizedTest(name = "{0} consecutive failures leave the account usable")
    @ValueSource(ints = {1, 2, 3, 4})
    void BR03_fewerThanFiveConsecutiveFailures_doNotLockTheAccount(int failures) {
        UserAccount account = activeVerifiedAccount();

        for (int i = 0; i < failures; i++) {
            assertThat(account.recordFailedLogin(NOW))
                    .as("failure %d did not reach the limit", i + 1)
                    .isFalse();
        }

        assertThat(account.getFailedLoginCount()).isEqualTo(failures);
        assertThat(account.isLockedOutAt(NOW)).isFalse();
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void BR03_theFifthConsecutiveFailure_locksTheAccountForFifteenMinutes() {
        UserAccount account = activeVerifiedAccount();

        for (int i = 0; i < 4; i++) {
            account.recordFailedLogin(NOW);
        }
        boolean lockedByTheFifth = account.recordFailedLogin(NOW);

        assertThat(lockedByTheFifth).isTrue();
        assertThat(account.getFailedLoginCount()).isEqualTo(5);
        assertThat(account.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(account.isLockedOutAt(NOW)).isTrue();
    }

    /**
     * The two numbers BR-03 states, asserted as the constants they are declared as and not only
     * through what they cause. A behavioural test alone keeps passing when the threshold is changed
     * and the loop above is changed with it.
     */
    @Test
    void BR03_theThresholdAndTheWindow_areTheValuesTheRuleStates() {
        assertThat(UserAccount.MAX_CONSECUTIVE_FAILED_LOGINS)
                .as("SRS 3.2.3: after 5 consecutive failed login attempts")
                .isEqualTo(5);
        assertThat(UserAccount.LOCKOUT_DURATION)
                .as("SRS 3.2.3: the account cannot log in for 15 minutes")
                .isEqualTo(Duration.ofMinutes(15));
    }

    /** The end instant is outside the lockout: locked for fifteen minutes is not locked at fifteen. */
    @Test
    void BR03_theLockoutBoundary_isExclusiveAtItsEndInstant() {
        UserAccount account = lockedOutAt(NOW);
        Instant endsAt = NOW.plus(Duration.ofMinutes(15));

        assertThat(account.isLockedOutAt(endsAt.minusMillis(1)))
                .as("one millisecond before the end, still locked")
                .isTrue();
        assertThat(account.isLockedOutAt(endsAt))
                .as("at the end instant itself, no longer locked")
                .isFalse();
        assertThat(account.isLockedOutAt(endsAt.plusMillis(1))).isFalse();
    }

    /** And nothing has to run for it to end. The columns are tidied by the next attempt, not by a job. */
    @Test
    void BR03_aLockoutEnds_withoutAnyoneUnlockingTheAccount() {
        UserAccount account = lockedOutAt(NOW);
        Instant afterwards = NOW.plus(Duration.ofMinutes(15));

        assertThat(account.getAccountStatus())
                .as("a BR-03 lockout is not the LOCKED status an administrator sets")
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isLockedOutAt(afterwards)).isFalse();
        assertThatCode(() -> account.recordLogin(afterwards)).doesNotThrowAnyException();
    }

    /** A sign-in during the lockout is refused even when the password was right (MSG09). */
    @Test
    void BR03_signingInDuringTheLockout_isRefusedWithTheMinutesStillToWait() {
        UserAccount account = lockedOutAt(NOW);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> account.recordLogin(NOW.plus(Duration.ofMinutes(5))))
                .satisfies(refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.LOGIN_TEMPORARILY_LOCKED);
                    assertThat(refusal.messageArgs())
                            .as("MSG09 names the minutes still to wait")
                            .containsExactly("10");
                });
    }

    /**
     * MSG09's minutes round up, because the message is an instruction: told to wait 0 minutes with
     * 30 seconds left, the holder tries again too early and is refused again.
     */
    @ParameterizedTest(name = "{0} seconds remaining is reported as {1} minutes")
    @CsvSource({"900,15", "841,15", "840,14", "61,2", "60,1", "59,1", "1,1"})
    void BR03_theMinutesMsg09Names_areRoundedUp(long secondsRemaining, long expectedMinutes) {
        UserAccount account = lockedOutAt(NOW);
        Instant at = NOW.plus(Duration.ofMinutes(15)).minusSeconds(secondsRemaining);

        assertThat(account.lockoutMinutesRemainingAt(at)).isEqualTo(expectedMinutes);
    }

    @Test
    void BR03_askingForTheMinutesOfAnAccountThatIsNotLockedOut_isRefused() {
        assertThatThrownBy(() -> activeVerifiedAccount().lockoutMinutesRemainingAt(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not locked out");
    }

    /** "A successful login resets the counter", and drops the lockout instant with it. */
    @Test
    void BR03_aSuccessfulSignIn_resetsTheCounter() {
        UserAccount account = activeVerifiedAccount();
        account.recordFailedLogin(NOW);
        account.recordFailedLogin(NOW);

        account.recordLogin(NOW);

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getLastLoginAt()).isEqualTo(NOW);
    }

    /**
     * After a lockout has been served, the run of failures it ended is over: the next mistake counts
     * as the first, not the sixth. Carrying the count forward would make one mistyped password
     * re-lock the account for another quarter of an hour, which would make BR-03's own word
     * "consecutive" untrue — recorded as an alignment item, because the SRS does not say either way.
     */
    @Test
    void BR03_afterALockoutIsServed_theNextFailureCountsAsTheFirst() {
        UserAccount account = lockedOutAt(NOW);
        Instant afterwards = NOW.plus(Duration.ofMinutes(15));

        boolean lockedAgain = account.recordFailedLogin(afterwards);

        assertThat(lockedAgain).isFalse();
        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getLockedUntil()).isNull();
    }

    /** A failure during the lockout is not counted twice over: the lock is still the lock. */
    @Test
    void BR03_aSecondRunOfFiveFailures_locksTheAccountAgain() {
        UserAccount account = lockedOutAt(NOW);
        Instant afterwards = NOW.plus(Duration.ofMinutes(15));

        for (int i = 0; i < 4; i++) {
            assertThat(account.recordFailedLogin(afterwards)).isFalse();
        }

        assertThat(account.recordFailedLogin(afterwards)).isTrue();
        assertThat(account.getLockedUntil()).isEqualTo(afterwards.plus(Duration.ofMinutes(15)));
    }

    @Test
    void BR03_recordingAFailureWithoutAnInstant_isRefused() {
        assertThatThrownBy(() -> activeVerifiedAccount().recordFailedLogin(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** An account that has just served five failures, with the clock at the moment of the fifth. */
    private static UserAccount lockedOutAt(Instant at) {
        UserAccount account = activeVerifiedAccount();
        for (int i = 0; i < 5; i++) {
            account.recordFailedLogin(at);
        }
        return account;
    }

    private static UserAccount activeVerifiedAccount() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");
        account.verifyEmail(NOW);
        return account;
    }

    private static UserAccount accountIn(AccountStatus status) {
        UserAccount account = activeVerifiedAccount();
        switch (status) {
            case ACTIVE -> {
                /* already there */
            }
            case LOCKED -> account.lock();
            case BANNED -> account.ban();
        }
        return account;
    }

    // ------------------------------------------------------------------ A-30

    /**
     * A-30: wrong current passwords on the Security tab are counted against BR-03's threshold, and the
     * fifth in a row is the one that answers {@code true} — the fourth does not.
     */
    @Test
    void A30_theFifthWrongCurrentPasswordInARow_reachesTheThreshold() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(account.recordFailedPasswordChange())
                    .as("attempt %d", attempt)
                    .isFalse();
        }
        assertThat(account.recordFailedPasswordChange()).isTrue();
        assertThat(account.getFailedPasswordChangeCount())
                .as("the count starts again")
                .isZero();
    }

    /** It is a separate count: the sign-in counter and the lockout of BR-03 are untouched. */
    @Test
    void A30_wrongCurrentPasswords_neverTouchTheSignInLockout() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");

        for (int attempt = 0; attempt < 5; attempt++) {
            account.recordFailedPasswordChange();
        }

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLockedUntil()).isNull();
    }

    /** A new password, however it was set, makes the earlier guesses moot and resets the count. */
    @Test
    void A30_changingThePassword_resetsTheCount() {
        UserAccount account = UserAccount.register("trader@example.invalid", "hash");
        account.recordFailedPasswordChange();
        account.recordFailedPasswordChange();

        account.changePassword("new-hash");

        assertThat(account.getFailedPasswordChangeCount()).isZero();
    }

    private static Consumer<UserAccount> actionOf(String action) {
        return switch (action) {
            case "lock" -> UserAccount::lock;
            case "unlock" -> UserAccount::unlock;
            case "ban" -> UserAccount::ban;
            default -> throw new IllegalArgumentException("unknown action " + action);
        };
    }
}

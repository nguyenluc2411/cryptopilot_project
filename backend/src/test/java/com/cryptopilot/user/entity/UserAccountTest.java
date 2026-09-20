package com.cryptopilot.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

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
 * write to the token table of another module and belongs to T-012. BR-01 is covered only as the
 * account's side of verification; the 24-hour window and the single-use link are the token's, and
 * live in {@code UserTokenTest}.
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

    private static Consumer<UserAccount> actionOf(String action) {
        return switch (action) {
            case "lock" -> UserAccount::lock;
            case "unlock" -> UserAccount::unlock;
            case "ban" -> UserAccount::ban;
            default -> throw new IllegalArgumentException("unknown action " + action);
        };
    }
}

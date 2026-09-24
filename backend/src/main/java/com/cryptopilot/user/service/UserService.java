package com.cryptopilot.user.service;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.LoginCredentials;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.user.UserRole;
import com.cryptopilot.user.UserSummary;
import com.cryptopilot.user.entity.AccountStatus;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.entity.UserProfile;
import com.cryptopilot.user.repository.UserAccountRepository;
import com.cryptopilot.user.repository.UserProfileRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The module's own use cases, and the implementation of what it publishes to other modules.
 *
 * <h2>Transactions</h2>
 *
 * <p>The boundary is here — not in the controller, which has no idea how many rows a request
 * touches, and not in the repositories, which each see one table. Two methods write:
 *
 * <ul>
 *   <li>{@link #registerTrader} writes {@code user_account} and {@code user_profile}. They are one
 *       unit of work: UC-01 says an account is created <em>with</em> a profile, and an account
 *       without one would break every screen that reads a display name. Either both rows commit or
 *       neither does.
 *   <li>{@link #markEmailVerified} writes {@code user_account}, through the entity's own
 *       transition rather than by assignment, so BR-01's "already verified" case is refused by the
 *       account and not by an {@code if} here.
 *   <li>{@link #recordLoginAttempt} writes {@code user_account} twice over, in two different
 *       transactions, and that is the whole difficulty of it — see below.
 * </ul>
 *
 * <h2>A sign-in writes on the path that fails</h2>
 *
 * <p>Every other write here happens because something succeeded. BR-03's counter is the opposite: it
 * has to be recorded precisely when the request is refused, and a refusal is an exception, so the
 * obvious arrangement — increment, then throw, in one transaction — rolls the increment back and the
 * account is never locked however many passwords are tried. {@link FailedLoginRecorder} exists to
 * commit that one write in a transaction of its own, and the test that asserts the counter after a
 * rejected attempt is what keeps it committed.
 *
 * <p>The order of the checks decides which message SRS 5.3 assigns, and is stated in
 * {@link UserApi#recordLoginAttempt}: lockout, then password, then status, then verification.
 *
 * <h2>The duplicate address</h2>
 *
 * <p>Checked first and caught afterwards, which is two things for two situations. The check turns
 * the ordinary case — somebody registering twice — into MSG04 without touching the database with a
 * doomed insert. The catch handles the case the check cannot: two registrations for the same
 * address arriving together, both passing the check, one of them losing to
 * {@code uq_user_account_email_lower}. Without the catch the loser would receive the generic
 * conflict instead of the message the SRS assigns, and the two callers would get different answers
 * to the same request.
 *
 * <p>The catch only works because the insert is flushed inside it. An account assigns its own key,
 * so {@code save} alone sends no statement and the index would refuse the row at commit — after
 * this method, and after the {@code try}. The explicit flush is what puts the refusal where it can
 * be read as MSG04, and a test holds that arrangement in place.
 *
 * <p>Rule: BR-01, BR-05; SRS UC-01 (MSG04).
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer"; "Unit of Work" — the transaction spans the use case, not the
 * table).
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 4 and 14 (an
 * application service coordinates; the rules stay on the entities).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserService implements UserApi {

    private final UserAccountRepository accounts;
    private final UserProfileRepository profiles;
    private final FailedLoginRecorder failedLogins;
    private final DeviceService devices;

    @Override
    @Transactional
    public UserSummary registerTrader(String email, String passwordHash, String displayName) {
        if (accounts.existsByEmailIgnoringCase(email)) {
            throw alreadyRegistered(email, null);
        }
        UserAccount account = UserAccount.register(email, passwordHash);
        UserProfile profile = UserProfile.createFor(account.getId(), displayName);
        try {
            accounts.save(account);
            accounts.flush();
            profiles.save(profile);
            return summaryOf(account);
        } catch (DataIntegrityViolationException lostTheRace) {
            throw alreadyRegistered(email, lostTheRace);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        return accounts.findByEmailIgnoringCase(email).map(UserService::summaryOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginCredentials> findCredentialsByEmail(String email) {
        return accounts.findByEmailIgnoringCase(email)
                .map(account -> new LoginCredentials(account.getId(), account.getPasswordHash()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginCredentials> findCredentialsById(UUID userId) {
        return accounts.findById(userId)
                .map(account -> new LoginCredentials(account.getId(), account.getPasswordHash()));
    }

    @Override
    @Transactional
    public UserSummary recordLoginAttempt(UUID userId, boolean passwordMatched, Instant at) {
        UserAccount account =
                accounts.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));

        if (account.isLockedOutAt(at)) {
            throw lockedOut(account, at);
        }
        if (!passwordMatched) {
            throw failedLogins
                    .record(userId, at)
                    .map(minutes -> new BusinessException(
                            ErrorCode.LOGIN_TEMPORARILY_LOCKED,
                            "too many consecutive failed sign-in attempts",
                            minutes))
                    .orElseGet(() ->
                            new BusinessException(ErrorCode.INVALID_CREDENTIALS, UserApi.WRONG_CREDENTIALS_DETAIL));
        }

        account.recordLogin(at);
        accounts.save(account);
        return summaryOf(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findForSessionRenewal(UUID userId) {
        return accounts.findById(userId)
                .filter(account -> account.getAccountStatus() == AccountStatus.ACTIVE)
                .filter(UserAccount::isEmailVerified)
                .map(UserService::summaryOf);
    }

    @Override
    @Transactional
    public void markEmailVerified(UUID userId, Instant verifiedAt) {
        UserAccount account =
                accounts.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
        account.verifyEmail(verifiedAt);
        accounts.save(account);
    }

    /**
     * BR-04's write half. The rule's other half - that the reset revokes every session - is the
     * caller's, because the tokens are another module's table, and the two happen in one transaction
     * that the caller opens: a reset that committed the password and lost the revocation would leave
     * the old sessions alive, which is the one outcome BR-04 exists to prevent.
     */
    @Override
    @Transactional
    public void changePassword(UUID userId, String newPasswordHash) {
        UserAccount account =
                accounts.findById(userId).orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
        account.changePassword(newPasswordHash);
        accounts.save(account);
    }

    /** An account that has vanished counts nothing and reaches no threshold. */
    @Override
    @Transactional
    public boolean recordFailedPasswordChange(UUID userId) {
        return accounts.findById(userId)
                .map(account -> {
                    boolean reached = account.recordFailedPasswordChange();
                    accounts.save(account);
                    return reached;
                })
                .orElse(false);
    }

    /**
     * Delegates to {@link DeviceService}, which owns the device rows; this class is only the module's
     * published face. It joins the caller's transaction, so the device and the refresh tokens of a
     * sign-out commit together.
     */
    @Override
    @Transactional
    public void deactivateDevice(UUID userId, String fcmToken) {
        devices.deactivateByToken(userId, fcmToken);
    }

    /**
     * The same answer whichever way the duplicate was noticed, so that the winner and the loser of
     * a race are told the same thing. The address is not repeated in the detail: it is the caller's
     * own input, the log already carries the request, and a message is not the place to echo one.
     */
    private static BusinessException alreadyRegistered(String email, Throwable cause) {
        return new BusinessException(
                ErrorCode.EMAIL_ALREADY_REGISTERED,
                "an account already holds the address offered for registration",
                cause);
    }

    /**
     * MSG09, with the minutes still to wait. The account is asked for them rather than the duration
     * being recomputed here, because the account is what knows when the lockout ends.
     */
    private static BusinessException lockedOut(UserAccount account, Instant at) {
        return new BusinessException(
                ErrorCode.LOGIN_TEMPORARILY_LOCKED,
                "the account is serving a temporary lockout",
                account.lockoutMinutesRemainingAt(at));
    }

    private static UserSummary summaryOf(UserAccount account) {
        return new UserSummary(
                account.getId(),
                account.getEmail(),
                UserRole.valueOf(account.getRole().name()),
                account.isEmailVerified());
    }
}

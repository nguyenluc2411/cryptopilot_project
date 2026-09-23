package com.cryptopilot.user.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/**
 * An account: the identity a person signs in with, and the lifecycle an administrator moves it
 * through.
 *
 * <h2>Aggregate</h2>
 *
 * <p>This is an aggregate root, and it is the root of the only aggregate the identity tables form.
 * The schema says so before any modelling does: {@code user_profile}, {@code user_device} and
 * {@code user_token} are the only three foreign keys to {@code user_account} that cascade on
 * delete, because they are the account itself rather than things it owns, while trading plans,
 * journals, orders, posts and the audit trail keep the default and make the delete fail. Rows that
 * refuse to be deleted with their author are separate aggregates that merely name one.
 *
 * <p>Of those three, only {@link UserProfile} and {@link UserDevice} are in this module;
 * {@code UserToken} belongs to {@code auth}, which owns the token table. A module reaches another
 * module only through its public API, so the token cannot hold an association to this class even
 * though the database ties their lifetimes together — it names the account by identifier, as every
 * reference across an aggregate boundary here does. The cascade in the database is what expresses
 * the lifetime coupling that the module boundary keeps out of the mapping.
 *
 * <p>No association is mapped in either direction, not even inside this module. Nothing in this
 * task navigates from an account to its profile or its devices, and an unnecessary association is
 * not free: it decides a fetch strategy, adds a second way to load the same row, and turns a query
 * over profiles into one query per profile the moment somebody makes it eager. The identifier is
 * the reference, and each side is loaded by the use case that needs it.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The transitions are methods, not setters, so that the rules live with the state they guard
 * rather than in whichever service happened to write the change. {@code ACTIVE} is where an
 * account starts and the only state a sign-in can succeed from (BR-06); {@code LOCKED} is
 * reversible; {@code BANNED} is terminal, because the administration screens offer Lock, Unlock and
 * Ban and no Unban.
 *
 * <p>Two rules this class deliberately does not enforce, because a single account cannot see what
 * they are about: that only an administrator may grant the ADMIN role (BR-05) is authorization and
 * belongs to the policy layer, and that the last active administrator may not be locked, banned or
 * demoted (BR-58) needs a count of the other accounts. Locking or banning also revokes every
 * session of the account (BR-06); this class records the status change, and the service that calls
 * it revokes the tokens, because the tokens are another module's table.
 *
 * <h2>The failed-attempt lockout (BR-03)</h2>
 *
 * <p>Two different things are called "locked" and this class keeps them apart. {@code LOCKED} is a
 * status an administrator sets and only an administrator clears (BR-05, BR-06). The BR-03 lockout is
 * not a status at all: it is a counter and an instant, it is set by the fifth wrong password in a
 * row, and it ends by itself when that instant passes. An account serving a BR-03 lockout is still
 * {@code ACTIVE}, which is why {@link #isLockedOutAt(Instant)} is asked separately from the status
 * and why nothing here calls {@link #unlock()} — there is no unlock event to wait for, only time.
 *
 * <p>Rule: BR-01, BR-03, BR-05, BR-06; TECHNICAL_DESIGN sections 2, 3.1 and 6.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 and 6
 * (entities, aggregates, and invariants enforced by the root).
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10
 * (keep aggregates small; reference other aggregates by identity).
 */
@Getter
@Entity
@Table(name = "user_account")
@AttributeOverride(name = "id", column = @Column(name = "user_id", nullable = false, updatable = false))
public class UserAccount extends BaseEntity {

    /**
     * BR-03: the account cannot log in after five consecutive failed attempts. The rule states the
     * number and never calls it configurable, so it lives beside the rule rather than in
     * {@code system_setting}, which holds the values an administrator may change without approval -
     * the same treatment BR-01's twenty-four hours gets. Changing this is changing BR-03.
     */
    static final int MAX_CONSECUTIVE_FAILED_LOGINS = 5;

    /** BR-03: and it cannot log in for fifteen minutes once that happens. */
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    /** The address the account signs in with. Unique, and unique case-insensitively (BR-01). */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** The encoded password. Never a password, and never encoded here. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** The one role this account holds (BR-05). */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    /** Where the account stands in its lifecycle (BR-05, BR-06). */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 32)
    private AccountStatus accountStatus;

    /** When the address was verified, or {@code null} while it has not been (BR-01). */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** When the account last signed in, or {@code null} if it never has. */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** How many sign-in attempts have been rejected in a row (BR-03). */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    /**
     * When the failed-attempt lockout ends, or {@code null} when the account has never been locked
     * out. A value in the past is a lockout that has already been served (BR-03).
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** For JPA only. */
    protected UserAccount() {}

    private UserAccount(String email, String passwordHash) {
        this.email = requireText(email, "email");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.role = Role.TRADER;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    /**
     * A newly registered account: role TRADER and status ACTIVE, with the address not yet verified.
     *
     * <p>The role is not a parameter. BR-05 says a self-registered account is always a TRADER and
     * only an administrator may assign ADMIN, so the only way to reach ADMIN is {@link
     * #changeRole(Role)} on an existing account — a registration form cannot ask for it, because
     * there is nothing here to ask.
     *
     * <p>The password arrives already hashed. This class never sees a password and never encodes
     * one; the encoder is the caller's, so that no entity has to know which one is configured.
     *
     * <p>Rule: BR-05; SRS UC-01.
     */
    public static UserAccount register(String email, String passwordHash) {
        return new UserAccount(email, passwordHash);
    }

    /**
     * Marks the address verified, which is what lets the account sign in (BR-01).
     *
     * <p>Refuses an account that is already verified. The link is single-use, so a second
     * verification is a link that has been used before, and the holder is told exactly that.
     *
     * <p>Rule: BR-01.
     */
    public void verifyEmail(Instant verifiedAt) {
        Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        if (emailVerifiedAt != null) {
            throw new BusinessException(
                    ErrorCode.EMAIL_ALREADY_VERIFIED, "account " + getId() + " verified its address already");
        }
        this.emailVerifiedAt = verifiedAt;
    }

    /**
     * Records a successful sign-in, after checking the three rules that decide whether there may be
     * one: the account is not serving a failed-attempt lockout (BR-03), it is ACTIVE (BR-06) and its
     * address is verified (BR-01). A success is also what ends a run of failures, so the counter goes
     * back to zero and any lockout instant is dropped.
     *
     * <p>The lockout is checked first, and that order is load-bearing rather than cosmetic: it is the
     * one check a <em>correct</em> password must not be allowed to override, and a caller reaches this
     * method only once the password matched. The service asks {@link #isLockedOutAt(Instant)} earlier
     * as well, so that a locked-out account is answered MSG09 instead of being told its password was
     * wrong; this check is what makes the rule hold even for a caller that forgets to.
     *
     * <p>Status comes before verification. An account that is locked or banned is told so and nothing
     * else; telling it to verify its address instead would offer a resend link to somebody who cannot
     * sign in whatever they do with it. The SRS states both messages and no order between them, so
     * this is the order that follows from what each message offers.
     *
     * <p>What this method does not do is check the password - that is the caller's, with the
     * configured encoder.
     *
     * <p>Rule: BR-01, BR-03, BR-06.
     */
    public void recordLogin(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        requireNotLockedOut(at);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "the account is " + accountStatus + " and cannot log in",
                    accountStatus.name());
        }
        if (emailVerifiedAt == null) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED, "the address has not been verified");
        }
        this.lastLoginAt = at;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /**
     * Counts one rejected sign-in attempt and locks the account for fifteen minutes at the fifth in a
     * row (BR-03).
     *
     * <p>Answers whether that attempt was the one that locked it, because the caller has two messages
     * to choose between and this method knows which applies: MSG08 for an ordinary wrong password,
     * MSG09 for the attempt after which nothing will work for a quarter of an hour.
     *
     * <p>A lockout that has run out is cleared here rather than by a scheduled job or an explicit
     * unlock, and the run of failures is considered to have ended with it, so the counter restarts at
     * one. BR-03 says five <em>consecutive</em> failures and says nothing about the counter once the
     * fifteen minutes are served; carrying the old count forward would make every single mistake after
     * a served lockout lock the account again, which would make the rule's own word "consecutive"
     * untrue and would turn a fifteen-minute penalty into a permanent one for anybody who mistypes
     * twice in an evening. It is recorded as an alignment item rather than treated as settled.
     *
     * <p>This writes, and it writes on the path that ends in a refusal. The caller has to commit it in
     * a transaction of its own: rolled back together with the exception that rejects the sign-in, the
     * counter would never advance and the lock would never arrive.
     *
     * <p>Rule: BR-03.
     *
     * <p>Reference: Grassi, P. A., Garcia, M. E. &amp; Fenton, J. L. (2017). NIST SP 800-63B,
     * <i>Digital Identity Guidelines: Authentication and Lifecycle Management</i>, section 5.2.2
     * (rate limiting: limit consecutive failed authentication attempts on a single account, and
     * release the account after a period rather than requiring administrative action).
     * <p>Reference: OWASP Application Security Verification Standard v4, requirement V2.2.1
     * (anti-automation controls against credential testing, with a soft lockout on repeated failures).
     *
     * @return {@code true} when this attempt was the one that locked the account
     */
    public boolean recordFailedLogin(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (lockedUntil != null && !isLockedOutAt(now)) {
            this.failedLoginCount = 0;
            this.lockedUntil = null;
        }
        this.failedLoginCount++;
        if (failedLoginCount < MAX_CONSECUTIVE_FAILED_LOGINS) {
            return false;
        }
        this.lockedUntil = now.plus(LOCKOUT_DURATION);
        return true;
    }

    /**
     * Whether the account is serving a failed-attempt lockout at this instant (BR-03).
     *
     * <p>The end instant itself is outside the lockout: an account locked for fifteen minutes is not
     * locked at fifteen minutes. No state changes here - a lockout ends because time passed, so
     * nothing has to run for it to end, and the columns are tidied by the next attempt.
     */
    public boolean isLockedOutAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * How many whole minutes MSG09 should tell the holder to wait, counted from this instant.
     *
     * <p>Rounded up, because the message is an instruction and rounding it down is wrong in the only
     * direction that matters: told to wait 0 minutes with 30 seconds left, or 14 with 14 minutes and
     * 30 seconds left, the holder tries again too early and is refused again.
     *
     * <p>Rule: BR-03; SRS message MSG09.
     *
     * @throws IllegalStateException when the account is not locked out, because MSG09 has nothing to
     *     say then
     */
    public long lockoutMinutesRemainingAt(Instant now) {
        if (!isLockedOutAt(now)) {
            throw new IllegalStateException("account " + getId() + " is not locked out at " + now);
        }
        return (Duration.between(now, lockedUntil).toSeconds() + 59) / 60;
    }

    /**
     * Suspends the account temporarily. Legal from ACTIVE only: an account that is already locked
     * has nothing to lock, and a banned one is not coming back.
     *
     * <p>The caller revokes the sessions of the account afterwards (BR-06). This class cannot: the
     * tokens are the {@code auth} module's table.
     *
     * <p>Rule: BR-05, BR-06.
     */
    public void lock() {
        requireStatus(AccountStatus.ACTIVE, "lock");
        this.accountStatus = AccountStatus.LOCKED;
    }

    /**
     * Returns a locked account to use. Legal from LOCKED only.
     *
     * <p>Rule: BR-05.
     */
    public void unlock() {
        requireStatus(AccountStatus.LOCKED, "unlock");
        this.accountStatus = AccountStatus.ACTIVE;
    }

    /**
     * Suspends the account permanently. Legal from ACTIVE and from LOCKED; there is no way back,
     * which is why banning an account that is already banned is refused rather than ignored.
     *
     * <p>The caller revokes the sessions of the account afterwards (BR-06).
     *
     * <p>Rule: BR-05, BR-06.
     */
    public void ban() {
        if (accountStatus == AccountStatus.BANNED) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID, "account " + getId() + " is banned already");
        }
        this.accountStatus = AccountStatus.BANNED;
    }

    /**
     * Replaces the stored password hash, for a reset from an emailed link (BR-04) and later for a
     * change from the Security tab (UC-07).
     *
     * <p>Legal from every status, which is a decision rather than an oversight. A locked or banned
     * account still may not sign in - BR-06 says so and {@link #recordLogin} enforces it - so letting
     * one replace a password it cannot use costs nothing, while refusing would tell the holder of a
     * reset link what state the account is in. The reset endpoint answers the same whatever happens,
     * and this method is what lets it.
     *
     * <p>What it deliberately does not touch is the BR-03 lockout. That rule says five consecutive
     * failures and that a successful <em>login</em> resets the counter; a reset is not a login, and
     * clearing the counter here would turn the reset endpoint into a way to shorten a lockout without
     * knowing the password. The lockout ends when its fifteen minutes end, as it always does.
     *
     * <p>The password arrives already hashed, exactly as it does at registration. This class never
     * sees a password and never encodes one.
     *
     * <p>Rule: BR-04.
     *
     * <p>Reference: Grassi, P. A., Garcia, M. E. &amp; Fenton, J. L. (2017). NIST SP 800-63B,
     * <i>Digital Identity Guidelines</i>, section 5.1.1.2 (a memorized secret is stored only as a
     * salted hash; the verifier replaces it without ever holding the secret).
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = requireText(newPasswordHash, "newPasswordHash");
    }

    /**
     * Assigns the role. Every account holds exactly one, so this replaces it rather than adding to
     * it (BR-05).
     *
     * <p>Who may call this is not decided here. BR-05 reserves granting ADMIN to an administrator
     * and BR-58 protects the last active one; both are about the caller and the rest of the table,
     * neither of which an account can see from the inside.
     *
     * <p>Rule: BR-05.
     */
    public void changeRole(Role newRole) {
        this.role = Objects.requireNonNull(newRole, "newRole must not be null");
    }

    /** Whether the address has been verified, which BR-01 requires before a sign-in. */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    private void requireNotLockedOut(Instant now) {
        if (isLockedOutAt(now)) {
            throw new BusinessException(
                    ErrorCode.LOGIN_TEMPORARILY_LOCKED,
                    "the account is serving a temporary lockout",
                    lockoutMinutesRemainingAt(now));
        }
    }

    private void requireStatus(AccountStatus required, String action) {
        if (accountStatus != required) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_STATUS_TRANSITION_INVALID,
                    "cannot " + action + " account " + getId() + ": it is " + accountStatus + ", not " + required);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

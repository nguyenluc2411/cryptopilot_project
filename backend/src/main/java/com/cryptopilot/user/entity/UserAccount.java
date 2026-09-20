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
import java.time.Instant;
import java.util.Objects;

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
 * <p>Rule: BR-01, BR-05, BR-06; TECHNICAL_DESIGN sections 2, 3.1 and 6.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 and 6
 * (entities, aggregates, and invariants enforced by the root).
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10
 * (keep aggregates small; reference other aggregates by identity).
 */
@Entity
@Table(name = "user_account")
@AttributeOverride(name = "id", column = @Column(name = "user_id", nullable = false, updatable = false))
public class UserAccount extends BaseEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 32)
    private AccountStatus accountStatus;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

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
     * Records a successful sign-in, after checking the two rules that decide whether there may be
     * one: the account has to be ACTIVE (BR-06) and its address has to be verified (BR-01).
     *
     * <p>The status is checked first. An account that is locked or banned is told so and nothing
     * else; telling it to verify its address instead would offer a resend link to somebody who
     * cannot sign in whatever they do with it. The SRS states both messages and no order between
     * them, so this is the order that follows from what each message offers.
     *
     * <p>What this method does not do is check the password — that is the caller's, with the
     * configured encoder — nor count failed attempts, which needs columns BR-03 does not have yet.
     *
     * <p>Rule: BR-01, BR-06.
     */
    public void recordLogin(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new BusinessException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE, "account " + getId() + " is " + accountStatus + " and cannot log in");
        }
        if (emailVerifiedAt == null) {
            throw new BusinessException(
                    ErrorCode.EMAIL_NOT_VERIFIED, "account " + getId() + " has not verified its address");
        }
        this.lastLoginAt = at;
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

    /** The address the account signs in with. Unique, and unique case-insensitively (BR-01). */
    public String getEmail() {
        return email;
    }

    /** The encoded password. Never a password, and never encoded here. */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** The one role this account holds (BR-05). */
    public Role getRole() {
        return role;
    }

    /** Where the account stands in its lifecycle (BR-05, BR-06). */
    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    /** When the address was verified, or {@code null} while it has not been (BR-01). */
    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    /** When the account last signed in, or {@code null} if it never has. */
    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    /** Whether the address has been verified, which BR-01 requires before a sign-in. */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
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

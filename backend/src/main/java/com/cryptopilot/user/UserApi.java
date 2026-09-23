package com.cryptopilot.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the {@code user} module lets other modules do with an account. Everything else in the module
 * — the entities, the repositories, the service — is internal, so this interface is the whole of
 * the surface and the only way another module writes {@code user_account} or {@code user_profile}.
 *
 * <p>That is not a formality. Registration is driven by {@code auth}, which owns the password
 * encoder and the verification token, but the two rows it creates belong to this module, and a
 * module writes only its own tables. Without this interface {@code auth} would either reach into
 * another module's repository or grow a second way to create an account.
 *
 * <p>No password crosses it, in either direction. {@link #registerTrader} takes a hash that the
 * caller has already produced with the configured encoder, and {@link #findCredentialsByEmail}
 * hands back a hash for the caller to compare against — so a raw password never leaves the service
 * that received it and this module never holds one even briefly. The hash goes the other way because
 * {@code auth} is where it came from: it owns the encoder and BR-02, it produced the stored value at
 * registration, and comparing belongs with encoding rather than being a second place that has to
 * know which encoder is configured.
 *
 * <h2>Why a sign-in is two calls</h2>
 *
 * <p>{@code auth} decides whether the password matched; this module decides what that means. The
 * split is not a preference — every rule a sign-in turns on is about the account (BR-01, BR-03,
 * BR-06) and every one of them writes {@code user_account}, which is this module's table and nobody
 * else's. So the caller compares, and then tells {@link #recordLoginAttempt} what it found; the
 * counter, the lockout and the last-login instant are written here, by the module that owns them.
 *
 * <p>Rule: BR-01, BR-03, BR-05, BR-06; TECHNICAL_DESIGN section 2 (a module is reached only through
 * the types in its root package).
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 (a bounded
 * context is entered through a defined interface).
 */
public interface UserApi {

    /**
     * The detail text of every refusal that means "the address or the password was wrong".
     *
     * <p>One sentence for both cases, and a constant rather than two strings that happen to match,
     * because the two responses have to be byte-for-byte identical. SRS 3.2.3 says MSG08 must not
     * reveal which field was wrong, and a problem detail carries a {@code detail} as well as a message
     * code — two sentences that differ would answer the question the shared message code refuses to.
     *
     * <p>It names no account and no address for the same reason, and because an account's identifier
     * is internal: a refusal is not the place to hand one out. Which case it was goes to the log,
     * where it identifies the account without telling the caller anything.
     */
    String WRONG_CREDENTIALS_DETAIL = "the address or the password offered is wrong";

    /**
     * Creates an account and the profile that belongs to it, in one transaction, as SRS UC-01
     * describes: role TRADER, status ACTIVE, address not yet verified.
     *
     * @param email the address, compared against existing accounts without regard to case
     * @param passwordHash the password as the configured encoder produced it, never a password
     * @param displayName the name shown wherever the account appears to others
     * @return the created account, without anything secret on it
     * @throws com.cryptopilot.common.exception.BusinessException with
     *     {@code EMAIL_ALREADY_REGISTERED} (MSG04) when an account already holds the address —
     *     whether that is noticed by the check or by the unique index
     */
    UserSummary registerTrader(String email, String passwordHash, String displayName);

    /** The account holding an address, compared without regard to case, or empty. */
    Optional<UserSummary> findByEmail(String email);

    /**
     * What a sign-in needs in order to check a password: the account's key and its stored hash, or
     * empty when no account holds the address (SRS 3.2.3, UC-03).
     *
     * <p>It answers nothing about whether the account may sign in. The lockout, the status and the
     * verification are decided by {@link #recordLoginAttempt} after the comparison, so that an
     * endpoint cannot be used to find out anything about an address by looking at what comes back
     * before a password was even offered.
     *
     * <p>The caller compares in constant work whether or not this returned a value: an unknown address
     * has to cost the same bcrypt verification as a known one, or the response time answers the
     * question "does this address have an account?" that MSG08 exists to refuse.
     */
    Optional<LoginCredentials> findCredentialsByEmail(String email);

    /**
     * Applies BR-03, BR-06 and BR-01 to one sign-in attempt whose password has already been checked,
     * and records what happened (SRS 3.2.3, UC-03).
     *
     * <p>The order is fixed here because it decides which message the holder sees. A lockout is
     * checked first and refuses the attempt whether or not the password was right, because a lockout
     * a correct password could walk through would not be one. A wrong password then counts against
     * BR-03 and is refused. Only a right password on an account that is not locked out reaches the
     * status and verification checks, so an unverified or banned account is never revealed to somebody
     * who does not already know its password.
     *
     * <p>The failure path writes, and its write has to survive the refusal: the counter is committed
     * in a transaction of its own, because one that rolled back with the exception would count nothing
     * and the lock would never arrive.
     *
     * @param passwordMatched what the caller's comparison against {@link LoginCredentials#passwordHash}
     *     found
     * @param at the instant of the attempt, from the injected clock
     * @return the account, now carrying its new last-login instant
     * @throws com.cryptopilot.common.exception.BusinessException with {@code INVALID_CREDENTIALS}
     *     (MSG08), {@code LOGIN_TEMPORARILY_LOCKED} (MSG09), {@code ACCOUNT_NOT_ACTIVE} (MSG10) or
     *     {@code EMAIL_NOT_VERIFIED} (MSG11)
     */
    UserSummary recordLoginAttempt(UUID userId, boolean passwordMatched, Instant at);

    /**
     * The account behind an existing session, if it may still hold one (SRS 3.2.3, BR-01, BR-06).
     *
     * <p>Empty when no such account exists, when it is LOCKED or BANNED, or when its address is not
     * verified. It answers one question — may this session continue? — rather than publishing the
     * status, because the status is a {@code user.entity} type and a caller reading it would be
     * deciding a rule that belongs here.
     *
     * <p>Renewing a session is not a new sign-in and deliberately does not touch BR-03: a refresh
     * token is either valid or it is not, and no password was offered to get wrong.
     *
     * <p>BR-06 also says a status change revokes the sessions of the account, which is the mechanism
     * that ends a banned user's access at once; this check is what keeps a session from outliving the
     * ban for the length of a refresh token if that revocation is ever missed.
     */
    Optional<UserSummary> findForSessionRenewal(UUID userId);

    /**
     * Records that the address of an account has been verified (BR-01).
     *
     * @throws com.cryptopilot.common.exception.BusinessException with
     *     {@code EMAIL_ALREADY_VERIFIED} (MSG07) when it already was, and
     *     {@code RESOURCE_NOT_FOUND} when no such account exists
     */
    void markEmailVerified(UUID userId, Instant verifiedAt);

    /**
     * Replaces the password of an account with an already encoded one (BR-04).
     *
     * <p>The hash is the caller's, because the encoder is configured in {@code auth} and an account
     * has no business knowing which one. This module owns the column and therefore owns the write;
     * {@code auth} decides that a reset link was valid and what the new password is, and asks here.
     *
     * <p>Every status is accepted. A locked or banned account cannot sign in whatever its password
     * is (BR-06), so refusing here would only tell the holder of a reset link something the reset
     * endpoint is built not to say.
     *
     * <p>Revoking the sessions of the account is <em>not</em> part of this. BR-04 requires it, but
     * the tokens are the {@code auth} module's table and the caller does it in the same transaction.
     *
     * @throws com.cryptopilot.common.exception.BusinessException with {@code RESOURCE_NOT_FOUND}
     *     when no such account exists
     */
    void changePassword(UUID userId, String newPasswordHash);

    /**
     * Stops push notifications to the installation holding this messaging token, when it belongs to
     * this account; does nothing otherwise (SRS 3.2.5: the device token is deactivated on logout).
     *
     * <p>{@code auth} calls it while ending a session, because a sign-out is where it learns whose
     * session it was and the device rows are this module's table. It never reports what it found: a
     * sign-out answers the same whatever the token it was given turned out to be, and so does this.
     */
    void deactivateDevice(UUID userId, String fcmToken);
}

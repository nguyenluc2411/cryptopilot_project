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
 * <p>No password crosses it. {@link #registerTrader} takes a hash that the caller has already
 * produced with the configured encoder, so a raw password never leaves the service that received
 * it and this module never holds one even briefly.
 *
 * <p>Rule: BR-01, BR-05; TECHNICAL_DESIGN section 2 (a module is reached only through the types in
 * its root package).
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 14 (a bounded
 * context is entered through a defined interface).
 */
public interface UserApi {

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
     * Records that the address of an account has been verified (BR-01).
     *
     * @throws com.cryptopilot.common.exception.BusinessException with
     *     {@code EMAIL_ALREADY_VERIFIED} (MSG07) when it already was, and
     *     {@code RESOURCE_NOT_FOUND} when no such account exists
     */
    void markEmailVerified(UUID userId, Instant verifiedAt);
}

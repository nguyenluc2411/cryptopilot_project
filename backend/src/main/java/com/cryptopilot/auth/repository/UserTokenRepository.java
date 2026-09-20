package com.cryptopilot.auth.repository;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collection of issued tokens. {@link UserToken} is an aggregate root of its own — the
 * {@code auth} module owns the table, and the account it belongs to is named by identifier because
 * a module may not map another module's entity — so it has a repository of its own.
 *
 * <h2>Only digests cross this interface</h2>
 *
 * <p>No method here takes, returns or logs a token. The value that was mailed out or handed to a
 * client exists in that mail and that client; what is stored and what is searched for is its
 * SHA-256 digest, so the caller hashes the presented value before it gets here. That is why the
 * parameter is called a digest and why {@link UserToken} refuses to be built from anything that is
 * not one: a lookup method taking a raw secret is how secrets end up in query logs and in the
 * slow-query log of whoever is debugging.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No {@code findAll}, no {@code deleteAll}: this table grows with every sign-in and every reset
 * request, and an unbounded read of it has no legitimate caller. The retention sweep of NSF-17 and
 * the family revocation of BR-06 both write in bulk, and both belong to the tasks that own those
 * rules, with the queries and the indexes their access pattern needs.
 *
 * <p>Nothing filters on expiry either. Whether a token may still be used is
 * {@link UserToken#isUsableAt(java.time.Instant)}, decided against the injected clock by the caller
 * that holds it; a repository predicate on {@code expires_at} would be a second copy of that rule,
 * evaluated against the database's clock instead, and the two would eventually disagree.
 *
 * <h2>Transactions</h2>
 *
 * <p>The lookups are read-only. The write path — issue a token, or consume one and verify an
 * address — spans two aggregates and belongs to the service's transaction, so {@code save} joins
 * the caller's rather than opening one of its own.
 *
 * <p>Rule: BR-01, BR-04; TECHNICAL_DESIGN sections 3.1, 5.3 and 7.15.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6.
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 12.
 */
public interface UserTokenRepository extends Repository<UserToken, UUID> {

    /**
     * The token with this digest, or empty.
     *
     * <p>Backed by {@code uq_user_token_hash}, the unique constraint on {@code token_hash}. Unique,
     * so at most one row matches.
     *
     * @param tokenDigest the SHA-256 digest of the presented value, never the value
     */
    @Transactional(readOnly = true)
    Optional<UserToken> findByTokenHash(String tokenDigest);

    /**
     * The token with this digest, if it is also of the expected kind.
     *
     * <p>The kind is part of the lookup rather than checked afterwards, so that a password reset
     * link presented to the verification endpoint finds nothing at all instead of finding a row the
     * caller then has to remember to reject. Three kinds share one table and one digest column, and
     * this is what keeps them from being interchangeable.
     *
     * <p>Backed by {@code uq_user_token_hash}: the unique index locates the single candidate row
     * and the type is a filter on it.
     *
     * <p>Rule: BR-01, BR-04.
     *
     * @param tokenDigest the SHA-256 digest of the presented value, never the value
     */
    @Transactional(readOnly = true)
    Optional<UserToken> findByTokenHashAndTokenType(String tokenDigest, TokenType tokenType);

    /**
     * The most recently issued token of this kind for this account, or empty if none was ever
     * issued.
     *
     * <p>This is what a resend decides against: how long ago the last verification mail went out
     * (UC-02). It returns the one row rather than the list, so the throttle cannot accidentally be
     * written over an unbounded read, and it says nothing about how long the window is — the window
     * is the caller's, measured against the injected clock.
     *
     * <p>Backed by {@code idx_user_token_user_type} on {@code (user_id, token_type)}: both
     * predicates are equalities on its leading columns. The ordering is not in the index, so the
     * few rows one account holds of one kind are sorted in memory; adding {@code created_at} to the
     * index would pay for an insert on every sign-in to save a sort over a handful of rows.
     *
     * <p>Rule: SRS UC-02 (resend is throttled).
     */
    @Transactional(readOnly = true)
    Optional<UserToken> findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(UUID userId, TokenType tokenType);

    /**
     * Writes a token, inserting it when it is new and updating it otherwise.
     *
     * <p>Not transactional here: consuming a token and verifying the address it belongs to have to
     * commit together, and that transaction is the service's.
     */
    UserToken save(UserToken token);
}

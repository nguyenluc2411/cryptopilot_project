package com.cryptopilot.auth.repository;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
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
 * request, and an unbounded read of it has no legitimate caller. The retention sweep of NSF-17 still
 * belongs to the task that owns it; the family revocation arrived with the rotation that needs it and
 * is {@link #revokeFamily}, written as one bulk update over the index its access pattern needs.
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
     * How many tokens of this kind the account was issued since an instant.
     *
     * <p>SRS 3.2.2 caps verification resends at five a day, and this is what the cap is counted
     * with. It answers a number, not rows: the caller wants to know how many, and handing back the
     * tokens instead would be an unbounded read dressed up as a count.
     *
     * <p>Backed by {@code idx_user_token_user_type}; the instant is a filter on the few rows one
     * account holds of one kind.
     *
     * <p>Rule: SRS 3.2.2 (five resends per day).
     */
    @Transactional(readOnly = true)
    @Query("select count(t) from UserToken t"
            + " where t.userId = :userId and t.tokenType = :tokenType and t.createdAt >= :since")
    int countIssuedSince(
            @Param("userId") UUID userId, @Param("tokenType") TokenType tokenType, @Param("since") Instant since);

    /**
     * Stops every unused token of this kind for this account from working, and answers how many
     * were stopped.
     *
     * <p>SRS 3.2.2: "a new token invalidates previous unused verification tokens". Without this,
     * every resend would leave another working link behind, and a link the owner has forgotten
     * about is a link an attacker who reaches an old mailbox can still use.
     *
     * <p>Written as one update rather than by loading the rows and calling the entity: there is no
     * business decision to make per row, the entity's own {@code markUsed} deliberately refuses an
     * expired token — which is exactly the kind this has to reach — and loading them would be the
     * unbounded read this interface does not offer.
     *
     * <p>It records the supersession in {@code used_at}, because that is the only column the schema
     * has for "no longer usable". A token that was superseded and one that was clicked are
     * therefore indistinguishable afterwards; nothing in BR-01 or UC-02 depends on telling them
     * apart, and an alignment item asks whether anything later will.
     *
     * <p>Rule: SRS 3.2.2.
     *
     * @return how many tokens stopped working
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now"
            + " where t.userId = :userId and t.tokenType = :tokenType and t.usedAt is null")
    int invalidateUnused(
            @Param("userId") UUID userId, @Param("tokenType") TokenType tokenType, @Param("now") Instant now);

    /**
     * Stops every unused token of one family from working, and answers how many were stopped.
     *
     * <p>This is the reaction to a replayed refresh token (TECHNICAL_DESIGN 7.15). Rotation retires a
     * token the moment it is redeemed, so the only way a retired one is presented is that somebody
     * kept a copy — and there is no way to tell whether the copy is in the legitimate client's hands
     * or somebody else's. Every token descended from that sign-in is therefore stopped, which ends
     * both sessions and forces a fresh sign-in with a password.
     *
     * <p>Backed by {@code idx_user_token_family}. One statement, because the revocation runs exactly
     * when an attacker may be holding a working token and the window between noticing and acting is
     * the window they have.
     *
     * <p>Written as an update rather than by loading the rows: there is no decision to make per row,
     * {@code markUsed} deliberately refuses an expired token -- which is a kind this has to reach, so
     * that an expired-but-unused sibling cannot be revived -- and loading them would be the unbounded
     * read this interface does not offer.
     *
     * <p>Rule: TECHNICAL_DESIGN 7.15; BR-06.
     *
     * @return how many tokens stopped working
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now where t.tokenFamilyId = :familyId and t.usedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Stops every unused refresh token of an account from working except those of one family, and
     * answers how many were stopped.
     *
     * <p>SRS 3.2.5: a password change from the Security tab revokes all <em>other</em> sessions. The
     * family kept is the caller's own, named by the access token it presented, so the person who
     * changed the password stays signed in on the device they did it from and nowhere else.
     *
     * <p>An update rather than a load, for the reasons {@link #invalidateUnused} gives.
     *
     * <p>Rule: SRS 3.2.5, UC-07.
     *
     * @return how many tokens stopped working
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now"
            + " where t.userId = :userId and t.tokenType = com.cryptopilot.auth.entity.TokenType.REFRESH"
            + " and t.usedAt is null and t.tokenFamilyId <> :keptFamilyId")
    int revokeOtherSessions(
            @Param("userId") UUID userId, @Param("keptFamilyId") UUID keptFamilyId, @Param("now") Instant now);

    /**
     * How many unused refresh tokens a session still holds — at most one while it is alive, because
     * rotation retires each token as it issues the next — and zero once it has ended: signed out,
     * revoked by reuse detection, by a password reset or by a password change.
     *
     * <p>This is the question every request with an access token asks through the session cache
     * ({@code LiveSessions}), so it is a count by the family index and nothing more. The account is
     * part of the condition as well as the family, so a token whose {@code sid} names somebody else's
     * session is refused rather than admitted.
     *
     * <p>Rule: SRS 3.2.3, 3.2.5; BR-04, BR-06; TECHNICAL_DESIGN 7.15.
     */
    @Transactional(readOnly = true)
    @Query("select count(t) from UserToken t where t.tokenFamilyId = :familyId and t.userId = :userId"
            + " and t.tokenType = com.cryptopilot.auth.entity.TokenType.REFRESH and t.usedAt is null")
    int countUnusedInSession(@Param("familyId") UUID familyId, @Param("userId") UUID userId);

    /**
     * Writes a token, inserting it when it is new and updating it otherwise.
     *
     * <p>Not transactional here: consuming a token and verifying the address it belongs to have to
     * commit together, and that transaction is the service's.
     */
    UserToken save(UserToken token);
}

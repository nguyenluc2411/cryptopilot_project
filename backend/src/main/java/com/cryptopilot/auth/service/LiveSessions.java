package com.cryptopilot.auth.service;

import com.cryptopilot.auth.config.TokenProperties;
import com.cryptopilot.auth.repository.UserTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Whether the session an access token names is still alive, answered from memory for most requests
 * and from the token table for the rest.
 *
 * <h2>Why an access token has to ask</h2>
 *
 * <p>An access token is a signed statement that stays valid until it expires. Ending a session revokes
 * its refresh tokens, which stops it being renewed, but a token already issued would otherwise go on
 * working for up to fifteen minutes — after a password change that SRS 3.2.5 says ends every other
 * session, after a reset that BR-04 says ends all of them, after a sign-out. So every access token
 * names its session in the {@code sid} claim (D-33) and the resource server asks this class whether
 * that session still holds an unused refresh token. A session that has none has ended, whatever
 * ended it.
 *
 * <h2>Why it is cached, and why the cache cannot answer wrongly for long</h2>
 *
 * <p>A lookup per request would put a query in front of every endpoint. So an answer is kept for
 * {@link #CACHE_TTL} — never longer than an access token lives — and each code path that ends sessions
 * evicts the account's entries once its transaction commits ({@link #evictAccountAfterCommit}). The
 * eviction is what makes revocation immediate; the time limit is only a backstop for an ending this
 * class was not told about.
 *
 * <p>Eviction runs after the commit, so a request that reads the table before the commit could still
 * store "alive" a moment after the eviction ran. Every answer therefore records the eviction
 * generation it was read under, and an answer read before the account's latest eviction is never
 * used. That closes the gap without a lock around the query.
 *
 * <p>The cache is in this process. The application runs as one instance (TECHNICAL_DESIGN 1.2); a
 * second instance would not hear another's evictions and would answer from its own entries for at
 * most {@link #CACHE_TTL}, which is the moment to move this to the shared cache.
 *
 * <p>Rule: SRS 3.2.3, 3.2.5, 4.2.4; BR-04, BR-06; D-33; TECHNICAL_DESIGN 5.3 and 7.15.
 *
 * <p>Reference: OWASP Application Security Verification Standard 4.0.3, requirement V3.3.3 (after a
 * successful password change the user can terminate all other active sessions).
 * <p>Reference: Jones, M. (2022). <i>OpenID Connect Front-Channel Logout 1.0</i>, section 3 (the
 * {@code sid} claim identifies a session at the issuer, so a relying party can end the session it
 * names).
 */
@Component
public class LiveSessions {

    /**
     * How long an answer is kept when nothing evicts it. A minute: long enough that a burst of
     * requests from one screen costs one query, short enough that an ending nobody reported is noticed
     * soon. Capped at the access token's lifetime by the constructor.
     */
    static final Duration CACHE_TTL = Duration.ofMinutes(1);

    /**
     * Past this many entries, expired ones are swept on the next write. The eviction markers are never
     * swept: one number per account that has ever ended a session is small, and dropping one could let
     * a stale answer that was being read at that moment be used.
     */
    static final int SWEEP_THRESHOLD = 10_000;

    private final UserTokenRepository tokens;
    private final Clock clock;
    private final Duration ttl;

    private final Map<UUID, Answer> answers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> evictedAtGeneration = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    LiveSessions(UserTokenRepository tokens, TokenProperties tokenProperties, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
        Duration accessTtl = tokenProperties.accessTokenTtl();
        this.ttl = CACHE_TTL.compareTo(accessTtl) <= 0 ? CACHE_TTL : accessTtl;
    }

    /**
     * Whether this account's session still holds an unused refresh token.
     *
     * @param sessionId the {@code sid} claim of the access token
     * @param userId the subject of the same token; a session of another account is not alive for it
     */
    public boolean isAlive(UUID sessionId, UUID userId) {
        Instant now = clock.instant();
        Answer cached = answers.get(sessionId);
        if (cached != null && cached.usableFor(userId, now, lastEvictionOf(userId))) {
            return cached.alive();
        }
        long readUnder = generation.get();
        boolean alive = tokens.countUnusedInSession(sessionId, userId) > 0;
        remember(sessionId, new Answer(userId, alive, now.plus(ttl), readUnder));
        return alive;
    }

    /**
     * Forgets every answer about this account's sessions once the current transaction commits, or at
     * once when there is none. Called by every path that ends sessions, so that the next request of an
     * ended session is refused rather than served from memory.
     */
    public void evictAccountAfterCommit(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAccount(userId);
                }
            });
        } else {
            evictAccount(userId);
        }
    }

    private void evictAccount(UUID userId) {
        evictedAtGeneration.put(userId, generation.incrementAndGet());
        answers.values().removeIf(answer -> answer.userId().equals(userId));
    }

    private long lastEvictionOf(UUID userId) {
        return evictedAtGeneration.getOrDefault(userId, -1L);
    }

    private void remember(UUID sessionId, Answer answer) {
        if (answers.size() >= SWEEP_THRESHOLD) {
            Instant now = clock.instant();
            answers.values().removeIf(old -> !old.expiresAt().isAfter(now));
        }
        answers.put(sessionId, answer);
    }

    /**
     * One answer, the account it was about, until when it may be used, and the generation it was read
     * under — an answer read before the account's latest eviction is stale however young it is.
     */
    private record Answer(UUID userId, boolean alive, Instant expiresAt, long readUnder) {

        boolean usableFor(UUID caller, Instant now, long lastEviction) {
            return userId.equals(caller) && now.isBefore(expiresAt) && readUnder >= lastEviction;
        }
    }
}

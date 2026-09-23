package com.cryptopilot.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.config.TokenProperties;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.support.MutableTestClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The session cache in isolation: that it answers from memory within its time limit, that it asks
 * the table again once the account is evicted or the limit has passed, and that an answer read under
 * an older eviction is never used (D-33).
 *
 * <p>The repository is a counting stand-in built from a dynamic proxy, because what is asserted is
 * how many times the table would be asked — the point of the cache — and the stand-in answers only
 * the one query this class makes.
 *
 * <p>Rule: SRS 3.2.5, 4.2.4; D-33.
 */
class LiveSessionsTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    private static final UUID SESSION = UUID.fromString("019b76da-a800-7000-8000-00000000a001");

    private static final UUID ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-00000000b001");

    private final AtomicInteger lookups = new AtomicInteger();

    private final AtomicBoolean alive = new AtomicBoolean(true);

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private final LiveSessions sessions =
            new LiveSessions(countingRepository(), properties(Duration.ofMinutes(15)), clock);

    /** A second request inside the time limit is answered from memory: one lookup for two requests. */
    @Test
    void D33_repeatedRequests_askTheTableOnce() {
        assertThat(sessions.isAlive(SESSION, ACCOUNT)).isTrue();
        assertThat(sessions.isAlive(SESSION, ACCOUNT)).isTrue();

        assertThat(lookups).hasValue(1);
    }

    /** Evicting the account is what makes an ending immediate: the next request asks again. */
    @Test
    void D33_afterAnEviction_theNextRequestAsksAgainAndSeesTheEnding() {
        sessions.isAlive(SESSION, ACCOUNT);
        alive.set(false);

        sessions.evictAccountAfterCommit(ACCOUNT);

        assertThat(sessions.isAlive(SESSION, ACCOUNT)).isFalse();
        assertThat(lookups).hasValue(2);
    }

    /** Without an eviction the answer lasts exactly the time limit and not an instant longer. */
    @Test
    void D33_anAnswer_isKeptForTheTimeLimitAndNoLonger() {
        sessions.isAlive(SESSION, ACCOUNT);

        clock.advance(LiveSessions.CACHE_TTL.minusNanos(1));
        sessions.isAlive(SESSION, ACCOUNT);
        assertThat(lookups).hasValue(1);

        clock.advance(Duration.ofNanos(1));
        sessions.isAlive(SESSION, ACCOUNT);
        assertThat(lookups).hasValue(2);
    }

    /** The limit is never longer than an access token lives, whatever that lifetime is configured to. */
    @Test
    void D33_theTimeLimit_isCappedByTheAccessTokenLifetime() {
        LiveSessions shortLived = new LiveSessions(countingRepository(), properties(Duration.ofSeconds(10)), clock);
        shortLived.isAlive(SESSION, ACCOUNT);

        clock.advance(Duration.ofSeconds(10));
        shortLived.isAlive(SESSION, ACCOUNT);

        assertThat(lookups).hasValue(2);
    }

    /** Evicting one account leaves another account's answers in memory. */
    @Test
    void D33_anEviction_reachesOnlyItsOwnAccount() {
        UUID otherSession = UUID.fromString("019b76da-a800-7000-8000-00000000a002");
        UUID otherAccount = UUID.fromString("019b76da-a800-7000-8000-00000000b002");
        sessions.isAlive(SESSION, ACCOUNT);
        sessions.isAlive(otherSession, otherAccount);

        sessions.evictAccountAfterCommit(ACCOUNT);
        sessions.isAlive(otherSession, otherAccount);

        assertThat(lookups).hasValue(2);
    }

    /**
     * The race the generation exists for: a lookup that started before an ending committed, and whose
     * "alive" is stored after the eviction ran, must not be used. Simulated by evicting while the
     * lookup is in progress.
     */
    @Test
    void D33_anAnswerReadBeforeTheLatestEviction_isNeverUsed() {
        LiveSessions[] self = new LiveSessions[1];
        AtomicBoolean evictDuringLookup = new AtomicBoolean(true);
        UserTokenRepository racing = repository(() -> {
            if (evictDuringLookup.getAndSet(false)) {
                self[0].evictAccountAfterCommit(ACCOUNT);
            }
            return alive.get() ? 1 : 0;
        });
        self[0] = new LiveSessions(racing, properties(Duration.ofMinutes(15)), clock);

        self[0].isAlive(SESSION, ACCOUNT);
        self[0].isAlive(SESSION, ACCOUNT);

        assertThat(lookups)
                .as("the first answer was stale on arrival, so the second request asked again")
                .hasValue(2);
    }

    /** A session is alive only for the account it belongs to: the same id under another subject is asked afresh. */
    @Test
    void D33_aCachedAnswer_isNotReusedForAnotherAccount() {
        sessions.isAlive(SESSION, ACCOUNT);

        sessions.isAlive(SESSION, UUID.fromString("019b76da-a800-7000-8000-00000000b009"));

        assertThat(lookups).hasValue(2);
    }

    /**
     * Memory stays bounded: once the cache is full, expired answers are swept on the next write, and
     * the answers still inside their time limit are kept.
     */
    @Test
    void D33_aFullCache_sweepsExpiredAnswersAndKeepsLiveOnes() {
        for (int i = 0; i < LiveSessions.SWEEP_THRESHOLD; i++) {
            sessions.isAlive(new UUID(1L, i), ACCOUNT);
        }
        clock.advance(LiveSessions.CACHE_TTL.dividedBy(2));
        sessions.isAlive(SESSION, ACCOUNT);
        clock.advance(LiveSessions.CACHE_TTL.dividedBy(2));
        int before = lookups.get();

        sessions.isAlive(UUID.fromString("019b76da-a800-7000-8000-00000000a003"), ACCOUNT);
        sessions.isAlive(SESSION, ACCOUNT);

        assertThat(lookups.get() - before)
                .as("the new session was looked up; the answer written half a limit ago survived the sweep")
                .isEqualTo(1);
    }

    private UserTokenRepository countingRepository() {
        return repository(() -> alive.get() ? 1 : 0);
    }

    private UserTokenRepository repository(java.util.function.IntSupplier unusedTokens) {
        return (UserTokenRepository) Proxy.newProxyInstance(
                UserTokenRepository.class.getClassLoader(),
                new Class<?>[] {UserTokenRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("countUnusedInSession")) {
                        lookups.incrementAndGet();
                        return unusedTokens.getAsInt();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static TokenProperties properties(Duration accessTokenTtl) {
        return new TokenProperties(
                accessTokenTtl, Duration.ofDays(7), Duration.ofDays(30), "a-signing-key-of-at-least-32-characters!");
    }
}

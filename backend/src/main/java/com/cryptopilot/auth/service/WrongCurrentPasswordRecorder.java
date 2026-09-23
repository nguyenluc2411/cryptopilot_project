package com.cryptopilot.auth.service;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.user.UserApi;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts a wrong current password on the Security tab in a transaction of its own, and ends every
 * session of the account at the fifth in a row (SRS 3.2.5; A-30).
 *
 * <p>A class of its own for the reason {@code FailedLoginRecorder} gives for BR-03: the write happens on
 * the path that ends in a refusal, and a refusal is an exception, so in the caller's transaction it
 * would be rolled back and the threshold would never arrive. {@code REQUIRES_NEW} commits it whatever
 * the caller does next, and a separate bean is what makes the annotation take effect at all.
 *
 * <h2>What the threshold does</h2>
 *
 * <p>Every refresh token of the account is revoked — the caller's own session included — and the
 * session cache is evicted once that commits, so every access token of the account is refused on its
 * next request. The count starts again at zero with the same commit. The sign-in is <em>not</em>
 * locked: the person guessing holds a session, very possibly one that is not theirs, and locking the
 * sign-in would keep out the owner, the one person who knows the password. They sign in again; the
 * guesser cannot.
 *
 * <p>Rule: SRS 3.2.5, UC-07; BR-03 (the threshold, not the lockout); A-30; D-33.
 *
 * <p>Reference: Grassi, P. A., Garcia, M. E. &amp; Fenton, J. L. (2017). NIST SP 800-63B, <i>Digital
 * Identity Guidelines</i>, section 5.2.2 (rate limiting: limit consecutive failed authentication
 * attempts on a single account).
 * <p>Reference: OWASP Application Security Verification Standard 4.0.3, requirement V2.2.1
 * (anti-automation controls effective against credential guessing).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class WrongCurrentPasswordRecorder {

    private static final Logger log = LoggerFactory.getLogger(WrongCurrentPasswordRecorder.class);

    private final UserApi users;
    private final UserTokenRepository tokens;
    private final LiveSessions liveSessions;

    /**
     * Records the attempt and commits it; at the threshold, ends every session of the account in the
     * same transaction.
     *
     * @return {@code true} when this attempt ended the account's sessions
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean record(UUID userId, Instant at) {
        if (!users.recordFailedPasswordChange(userId)) {
            return false;
        }
        int revoked = tokens.invalidateUnused(userId, TokenType.REFRESH, at);
        liveSessions.evictAccountAfterCommit(userId);
        log.warn("Repeated wrong current password for account {}: ended all {} sessions", userId, revoked);
        return true;
    }
}

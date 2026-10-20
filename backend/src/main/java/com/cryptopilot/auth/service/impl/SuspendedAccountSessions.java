package com.cryptopilot.auth.service.impl;

import com.cryptopilot.auth.model.enums.TokenType;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.auth.service.LiveSessions;
import com.cryptopilot.user.event.AccountSuspended;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ends every session of a banned account (BR-06). The listener runs inside the transaction that banned the account,
 * so the status and the revocation commit together: every refresh token is revoked in one statement, and the cached
 * liveness answers are evicted after the commit, so an access token already issued stops working at once rather than
 * when it expires.
 *
 * <p>Rule: BR-06; TECHNICAL_DESIGN 7.15; D-33.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SuspendedAccountSessions {

    private static final Logger log = LoggerFactory.getLogger(SuspendedAccountSessions.class);

    private final UserTokenRepository tokens;
    private final LiveSessions liveSessions;

    @EventListener
    public void on(AccountSuspended suspended) {
        int revoked = tokens.invalidateUnused(suspended.userId(), TokenType.REFRESH, suspended.suspendedAt());
        liveSessions.evictAccountAfterCommit(suspended.userId());
        log.info("Account {} was banned: revoked {} sessions", suspended.userId(), revoked);
    }
}

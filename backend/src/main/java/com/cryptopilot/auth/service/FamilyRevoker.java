package com.cryptopilot.auth.service;

import com.cryptopilot.auth.repository.UserTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a refresh token family in a transaction of its own (TECHNICAL_DESIGN 7.15).
 *
 * <h2>Why this cannot share the caller's transaction</h2>
 *
 * <p>Reuse detection revokes and then refuses, and the refusal is an exception. In one transaction
 * the rollback takes the revocation with it: the replay is answered MSG44, the family stays alive,
 * and whoever is holding the copied token simply presents the next one. The rule would read
 * correctly, pass a test that only checked the response, and protect nobody.
 *
 * <p>It is the same trap as BR-03's counter, in a different module and on a different table, and it
 * is solved the same way — {@code REQUIRES_NEW} commits the revocation before the exception is
 * raised, and a separate bean is what makes the annotation take effect, because a
 * {@code @Transactional} method called from inside the same object never goes through the proxy.
 *
 * <p>Logging out does not throw and would work either way. It goes through here regardless, so that
 * there is one way to revoke a family rather than two that differ in whether they survive a failure.
 *
 * <p>Rule: BR-06; TECHNICAL_DESIGN 7.15.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Unit of Work"; the work that must survive a failure cannot share the failing unit).
 * <p>Reference: Lodderstedt, T., Bradley, J., Labunets, A. &amp; Fett, D. (2025). <i>OAuth 2.0
 * Security Best Current Practice</i>, RFC 9700, section 4.14.2 (on detecting replay, revoke the whole
 * chain).
 */
@Component
class FamilyRevoker {

    private final UserTokenRepository tokens;

    FamilyRevoker(UserTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Stops every unused token of the family and commits it, whatever the caller does next.
     *
     * @return how many tokens stopped working
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int revoke(UUID tokenFamilyId, Instant now) {
        return tokens.revokeFamily(tokenFamilyId, now);
    }
}

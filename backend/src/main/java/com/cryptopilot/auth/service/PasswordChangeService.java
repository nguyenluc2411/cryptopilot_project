package com.cryptopilot.auth.service;

import com.cryptopilot.auth.PasswordPolicy;
import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.LoginCredentials;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing a password from the Security tab of SCR-07, by somebody who is signed in and knows the
 * current one (SRS UC-07, section 3.2.5).
 *
 * <h2>Why this is not on {@link AuthService}</h2>
 *
 * <p>UC-04 sits on {@code AuthService} because it shares the token table, the single-use rule and the
 * non-enumeration shape with the use cases around it (D-32). This one shares none of them: it has a
 * session, it names no address, it answers the caller exactly what went wrong, and it spends no
 * link. D-32 named it as the point to split, and it is.
 *
 * <h2>What it checks, in order</h2>
 *
 * <ol>
 *   <li>BR-02 on the new password, before anything is read, so a request that was never going to
 *       succeed costs no bcrypt comparison. {@link PasswordPolicy} rather than only the request
 *       record, as everywhere a password is set.
 *   <li>The current password, against the stored hash with the configured encoder, answered MSG08 when
 *       it is wrong (SRS 3.2.5). This is what separates UC-07 from a reset: holding a session is not
 *       enough to set a password, because a session can be left open on a borrowed laptop.
 * </ol>
 *
 * <p>A wrong current password does <em>not</em> count towards BR-03's lockout. BR-03 is about signing
 * in, and this caller already has; whether it should is recorded as an alignment item rather than
 * decided here (A-30).
 *
 * <h2>The other sessions</h2>
 *
 * <p>SRS 3.2.5: "on success all other sessions are revoked". The session kept is the one the access
 * token names in its {@code sid} claim — the family of the refresh token issued beside it — so the
 * person stays signed in where they made the change and nowhere else. A token without the claim is
 * one issued before the claim existed; for that one every session is revoked, which is the rule's
 * stronger reading and costs the caller one sign-in.
 *
 * <p>The new hash and the revocation commit in one transaction, for the reason BR-04 gives for the
 * reset: a password that changed while the old sessions survived is the outcome the rule exists to
 * prevent.
 *
 * <p>Rule: BR-02; SRS UC-07, sections 3.2.5 and 4.2.4; messages MSG03, MSG08.
 *
 * <p>Reference: Grassi, P. A., Garcia, M. E. &amp; Fenton, J. L. (2017). NIST SP 800-63B, <i>Digital
 * Identity Guidelines</i>, section 5.1.1.2 (a verifier requires the current secret before accepting a
 * new one).
 * <p>Reference: OWASP Application Security Verification Standard v4, requirements V2.1.5 and V3.3.3
 * (changing a password requires the current one; the user may terminate other active sessions after a
 * password change).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final UserApi users;
    private final UserTokenRepository tokens;
    private final LiveSessions liveSessions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Replaces the password of the signed-in account and ends its other sessions.
     *
     * @param userId the account the access token was issued to
     * @param currentSession the session the access token names, or {@code null} when it names none
     * @throws BusinessException {@code PASSWORD_POLICY_VIOLATION} (MSG03) or
     *     {@code CURRENT_PASSWORD_INCORRECT} (MSG08)
     * @throws ResourceNotFoundException when the account behind the token no longer exists
     */
    @Transactional
    public void changePassword(UUID userId, UUID currentSession, String currentPassword, String newPassword) {
        PasswordPolicy.requireCompliant(newPassword);
        LoginCredentials credentials = users.findCredentialsById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
        if (!passwordEncoder.matches(currentPassword, credentials.passwordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INCORRECT, "the current password offered is wrong");
        }

        Instant now = clock.instant();
        users.changePassword(userId, passwordEncoder.encode(newPassword));
        int revoked = currentSession == null
                ? tokens.invalidateUnused(userId, TokenType.REFRESH, now)
                : tokens.revokeOtherSessions(userId, currentSession, now);
        liveSessions.evictAccountAfterCommit(userId);
        log.info("Changed the password of account {}: revoked {} other sessions", userId, revoked);
    }
}

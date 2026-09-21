package com.cryptopilot.auth.service;

import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.user.UserSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and email verification: UC-01 and UC-02, and the resend that sits between them.
 *
 * <h2>Transactions</h2>
 *
 * <p>The boundary is here. The controller knows nothing about how many tables a request touches and
 * the repositories each see one; only a use case knows what has to commit together. Three methods
 * write:
 *
 * <ul>
 *   <li>{@link #register} writes the account and its profile — through {@code user}, which owns
 *       those tables — and the verification token, in one transaction. A failure anywhere leaves no
 *       account, no profile and no token.
 *   <li>{@link #verifyEmail} writes {@code user_token} (the token is spent) and
 *       {@code user_account} (the address is verified). Spending a link without verifying the
 *       address would burn it for nothing, so the two commit together.
 *   <li>{@link #resendVerification} stops the previous links working and writes a new one, which
 *       SRS 3.2.2 requires to happen as one step.
 * </ul>
 *
 * <h2>Passwords</h2>
 *
 * <p>A raw password reaches exactly one method, is checked against BR-02, is handed to the
 * configured encoder, and is not referenced again. It is not logged, not put in an exception
 * message, not returned in a response, and never crosses into {@code user}, which receives the hash
 * and has no idea what produced it.
 *
 * <h2>The link</h2>
 *
 * <p>The token is 256 random bits from a secure generator; the database stores only its digest.
 * The value itself leaves this class once, in the event that carries the mail, and is published
 * only after the transaction commits — SRS UC-01 is explicit that a mail that fails to send must
 * leave the account in place, and a mail sent for a transaction that then rolls back cannot be
 * recalled.
 *
 * <p>Every instant comes from the injected clock. The 24 hours of BR-01 is added to
 * {@code clock.instant()}, and the expiry is compared by the entity against an instant this class
 * passes in, so a test can place a request exactly on either side of the boundary.
 *
 * <p>Rule: BR-01, BR-02, BR-05; SRS UC-01, UC-02, sections 3.2.1 and 3.2.2; messages MSG03, MSG04,
 * MSG05, MSG06, MSG07.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 4 (an
 * application service is the use-case boundary and holds no business rules of its own).
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer"; "Unit of Work").
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 14
 * (publish a domain event once the fact it names is committed).
 * <p>Reference: OWASP Application Security Verification Standard v4, sections 2.1 and 2.5.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * BR-01: a verification link is valid for 24 hours. The rule states the number and never calls
     * it configurable, so it lives beside the rule rather than in {@code system_setting}, which
     * holds the values an administrator may change without approval. Changing this is changing
     * BR-01.
     */
    static final Duration VERIFICATION_TOKEN_LIFETIME = Duration.ofHours(24);

    /** SRS 3.2.2: a verification mail may be resent once per 60 seconds. */
    static final Duration RESEND_MINIMUM_INTERVAL = Duration.ofSeconds(60);

    /** SRS 3.2.2: and at most five times a day. */
    static final int RESEND_MAXIMUM_PER_DAY = 5;

    /**
     * The day the five-a-day cap is counted over. The SRS fixes no boundary for it; the one
     * boundary the SRS does fix anywhere is BR-50's daily AI quota, which resets at 00:00 UTC+7, so
     * the same one is used here rather than a second convention.
     */
    private static final ZoneId RESEND_DAY_ZONE = ZoneId.of("UTC+7");

    private final UserApi users;
    private final UserTokenRepository tokens;
    private final VerificationTokenFactory tokenFactory;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AuthService(
            UserApi users,
            UserTokenRepository tokens,
            VerificationTokenFactory tokenFactory,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.tokenFactory = tokenFactory;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates an account, its profile and a verification link (SRS UC-01).
     *
     * <p>The order is deliberate: the password is checked before anything is written, so a request
     * that was never going to succeed costs no insert, and the address is checked by {@code user},
     * which owns the constraint that actually decides it.
     *
     * @throws BusinessException {@code PASSWORD_POLICY_VIOLATION} (MSG03) or
     *     {@code EMAIL_ALREADY_REGISTERED} (MSG04)
     */
    @Transactional
    public void register(String email, String rawPassword, String displayName) {
        PasswordPolicy.requireCompliant(rawPassword);
        UserSummary account = users.registerTrader(email, passwordEncoder.encode(rawPassword), displayName);
        issueVerificationToken(account);
        log.info("Registered account {}", account.userId());
    }

    /**
     * Verifies an address from the link that was mailed out (SRS UC-02).
     *
     * <p>The presented value is hashed and the digest is looked up, together with the kind, so a
     * password reset link cannot verify an address. Unknown, already used and expired all answer
     * MSG07 and are indistinguishable on purpose: telling them apart would say whether a link ever
     * existed. The entity decides "used" and "expired"; this method only supplies the instant.
     *
     * @throws BusinessException {@code TOKEN_INVALID_OR_EXPIRED} (MSG07)
     */
    @Transactional
    public void verifyEmail(String presentedToken) {
        Instant now = clock.instant();
        UserToken token = tokens.findByTokenHashAndTokenType(
                        tokenFactory.digestOf(presentedToken), TokenType.EMAIL_VERIFICATION)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TOKEN_INVALID_OR_EXPIRED, "no verification token matches the presented value"));

        token.markUsed(now);
        tokens.save(token);
        users.markEmailVerified(token.getUserId(), now);
        log.info("Verified the address of account {}", token.getUserId());
    }

    /**
     * Sends the verification link again (SRS UC-02, section 3.2.2).
     *
     * <p>Answers the same whatever happens, and does so on purpose. An address nobody registered,
     * an address that is already verified, and an address that has asked too often all produce the
     * same response, so the endpoint cannot be used to discover which addresses hold accounts.
     * Registration is allowed to reveal that, because MSG04 says so explicitly on the form; an
     * endpoint that takes an address and nothing else is not.
     *
     * <p>SRS 3.2.2 caps this at once per 60 seconds and five a day, and requires a new link to
     * invalidate the previous unused ones — otherwise every resend would leave another working link
     * behind in a mailbox.
     */
    @Transactional
    public void resendVerification(String email) {
        Optional<UserSummary> found = users.findByEmail(email);
        if (found.isEmpty() || found.get().emailVerified()) {
            return;
        }
        UserSummary account = found.get();
        if (isThrottled(account)) {
            log.info("Resend refused for account {}: within the limits of SRS 3.2.2", account.userId());
            return;
        }
        tokens.invalidateUnused(account.userId(), TokenType.EMAIL_VERIFICATION, clock.instant());
        issueVerificationToken(account);
    }

    private boolean isThrottled(UserSummary account) {
        Instant now = clock.instant();
        Optional<Instant> lastIssued = tokens.findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(
                        account.userId(), TokenType.EMAIL_VERIFICATION)
                .map(UserToken::getCreatedAt);
        if (lastIssued.isPresent()
                && lastIssued.get().plus(RESEND_MINIMUM_INTERVAL).isAfter(now)) {
            return true;
        }
        int issuedToday = tokens.countIssuedSince(account.userId(), TokenType.EMAIL_VERIFICATION, startOfDay(now));
        return issuedToday >= RESEND_MAXIMUM_PER_DAY;
    }

    /** Midnight of the current day in the zone the daily cap is counted in. */
    private static Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, RESEND_DAY_ZONE)
                .atStartOfDay(RESEND_DAY_ZONE)
                .toInstant();
    }

    /**
     * Writes the token and announces the link. The event is published inside the transaction and
     * delivered after it commits, so nothing is mailed for a registration that rolled back.
     */
    private void issueVerificationToken(UserSummary account) {
        String rawToken = tokenFactory.newToken();
        Instant expiresAt = clock.instant().plus(VERIFICATION_TOKEN_LIFETIME);
        tokens.save(UserToken.issue(
                account.userId(), TokenType.EMAIL_VERIFICATION, tokenFactory.digestOf(rawToken), expiresAt));
        events.publishEvent(new VerificationTokenIssued(account.userId(), account.email(), rawToken, expiresAt));
    }
}

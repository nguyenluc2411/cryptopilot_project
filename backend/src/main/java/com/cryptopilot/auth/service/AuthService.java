package com.cryptopilot.auth.service;

import com.cryptopilot.auth.config.TokenProperties;
import com.cryptopilot.auth.entity.TokenType;
import com.cryptopilot.auth.entity.UserToken;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.auth.repository.UserTokenRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.user.LoginCredentials;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.user.UserSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity use cases that need no session of their own: registration and email verification
 * (UC-01, UC-02) and the resend between them, then signing in, renewing a session and signing out
 * (UC-03, UC-05).
 *
 * <h2>Sessions</h2>
 *
 * <p>A sign-in produces two tokens with different jobs. The access token is a signed JWT the client
 * sends with every request: short-lived, readable by whoever holds it, and checked without a database
 * read. The refresh token is a random 256-bit value stored only as a digest, presented to one
 * endpoint, and exchanged for a new pair — it is the only thing that can extend a session, which is
 * why it and not the access token is what logging out and BR-06 revoke.
 *
 * <p>Rotation is what makes a long-lived refresh token safe to hand out (TECHNICAL_DESIGN 7.15).
 * Every redemption retires the token it used and issues a successor in the same family, so a value
 * works exactly once. A retired value presented again therefore means two copies existed, and since
 * there is no way to tell the legitimate holder from whoever took a copy, the whole family is revoked
 * and both are sent back to the sign-in form. That is the detection an unrotated refresh token cannot
 * offer at all: a stolen one would be indistinguishable from the real one for its whole lifetime.
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
 *   <li>{@link #refresh} retires a token and inserts its successor, which have to commit together or
 *       a client is left holding a value that has been spent and no replacement.
 *   <li>{@link #logout} revokes a family.
 * </ul>
 *
 * <p>{@link #login} is the exception and is deliberately not transactional. Its one write on the
 * failure path — BR-03's counter — has to survive the refusal that follows it, so it commits in a
 * transaction of its own inside the {@code user} module; wrapping the whole sign-in in one would put
 * that counter back in the transaction the rejection rolls back.
 *
 * <h2>Passwords</h2>
 *
 * <p>A raw password reaches two methods — one to be encoded and stored, one to be compared against
 * what was stored — and is handed to the configured encoder in both. It is not referenced again. It is not logged, not put in an exception
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
 * <p>Rule: BR-01, BR-02, BR-03, BR-05, BR-06; SRS UC-01, UC-02, UC-03, UC-05, sections 3.2.1, 3.2.2
 * and 3.2.3; messages MSG03, MSG04, MSG05, MSG06, MSG07, MSG08, MSG09, MSG10, MSG11, MSG44;
 * TECHNICAL_DESIGN sections 5.3 and 7.15.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 4 (an
 * application service is the use-case boundary and holds no business rules of its own).
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Service Layer"; "Unit of Work").
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 14
 * (publish a domain event once the fact it names is committed).
 * <p>Reference: OWASP Application Security Verification Standard v4, sections 2.1, 2.5 and 3.2.
 * <p>Reference: Lodderstedt, T., Bradley, J., Labunets, A. &amp; Fett, D. (2025). <i>OAuth 2.0
 * Security Best Current Practice</i>, RFC 9700, section 4.14.2 (refresh token rotation: a replayed
 * token is detected by the server and the whole chain issued to that client is revoked).
 * <p>Reference: Lodderstedt, T., McGloin, M. &amp; Hunt, P. (2013). RFC 6819, <i>OAuth 2.0 Threat
 * Model and Security Considerations</i>, section 5.2.2.3 (refresh token replay detection: bind
 * successive tokens to one chain so that reuse of a retired one is noticed and the chain revoked).
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
     * The detail text of every refusal that means "this is not a live session", whichever of the four
     * reasons applies. One constant, so the four responses are identical byte for byte.
     */
    static final String SESSION_OVER_DETAIL = "the refresh token presented is not a live session";

    /**
     * The day the five-a-day cap is counted over. The SRS fixes no boundary for it; the one
     * boundary the SRS does fix anywhere is BR-50's daily AI quota, which resets at 00:00 UTC+7, so
     * the same one is used here rather than a second convention.
     */
    private static final ZoneId RESEND_DAY_ZONE = ZoneId.of("UTC+7");

    private final UserApi users;
    private final UserTokenRepository tokens;
    private final VerificationTokenFactory tokenFactory;
    private final RefreshTokenFactory refreshTokenFactory;
    private final FamilyRevoker familyRevoker;
    private final AccessTokenIssuer accessTokens;
    private final TokenProperties tokenProperties;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * A hash of a value nobody knows, compared against when no account holds the address offered.
     *
     * <p>Counting failures per account makes a sign-in endpoint a way of asking whether an address is
     * registered, if an unknown address is answered without doing the work a known one costs: bcrypt
     * at strength 12 takes tens of milliseconds and returning before it is measurable from outside.
     * So an unknown address is compared against this, the comparison fails as it was always going to,
     * and the two paths cost the same.
     *
     * <p>Generated once per application start from a fresh random value, and never stored anywhere.
     * A constant in the source would be a hash of a password somebody chose, and the whole point is
     * that no password matches it.
     */
    private final String hashOfNothing;

    AuthService(
            UserApi users,
            UserTokenRepository tokens,
            VerificationTokenFactory tokenFactory,
            RefreshTokenFactory refreshTokenFactory,
            FamilyRevoker familyRevoker,
            AccessTokenIssuer accessTokens,
            TokenProperties tokenProperties,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.tokenFactory = tokenFactory;
        this.refreshTokenFactory = refreshTokenFactory;
        this.familyRevoker = familyRevoker;
        this.accessTokens = accessTokens;
        this.tokenProperties = tokenProperties;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.hashOfNothing = passwordEncoder.encode(refreshTokenFactory.newToken());
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

    // ------------------------------------------------------------------- UC-03, UC-05

    /**
     * Signs an account in and opens a session (SRS UC-03, section 3.2.3).
     *
     * <p>The work is done in an order that keeps an endpoint taking an address from answering
     * questions about it. Every request costs one bcrypt verification, against the stored hash when an
     * account holds the address and against {@link #hashOfNothing} when none does, so the two are not
     * distinguishable by how long the answer takes. Only then does the {@code user} module apply
     * BR-03, BR-06 and BR-01 and decide which of MSG08, MSG09, MSG10 and MSG11 the holder sees — the
     * rules and the writes they make are about an account, so they live with the accounts.
     *
     * <p>The raw password reaches the encoder and nothing else. It is not logged, not put in an
     * exception message, not returned, and never crosses into {@code user}.
     *
     * @param rememberMe SRS 3.2.3: thirty days instead of seven for the refresh token. It changes
     *     nothing about the access token, which is fifteen minutes either way.
     * @throws BusinessException {@code INVALID_CREDENTIALS} (MSG08),
     *     {@code LOGIN_TEMPORARILY_LOCKED} (MSG09), {@code ACCOUNT_NOT_ACTIVE} (MSG10) or
     *     {@code EMAIL_NOT_VERIFIED} (MSG11)
     */
    public IssuedSession login(String email, String rawPassword, boolean rememberMe) {
        Instant now = clock.instant();
        Optional<LoginCredentials> credentials = users.findCredentialsByEmail(email);
        boolean passwordMatched = passwordEncoder.matches(
                rawPassword, credentials.map(LoginCredentials::passwordHash).orElse(hashOfNothing));

        if (credentials.isEmpty()) {
            log.info("Sign-in refused: no account holds the address offered");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, UserApi.WRONG_CREDENTIALS_DETAIL);
        }

        UserSummary account = users.recordLoginAttempt(credentials.get().userId(), passwordMatched, now);
        IssuedSession session = issueSession(account, UuidV7.next(), tokenProperties.refreshTokenTtl(rememberMe), now);
        log.info("Opened a session for account {}", account.userId());
        return session;
    }

    /**
     * Exchanges a refresh token for a new pair, retiring the one presented
     * (TECHNICAL_DESIGN 7.15).
     *
     * <p>The steps are that section's, in its order. An unknown digest, a token that has already been
     * used, an expired one and one belonging to an account that may no longer hold a session all end
     * the same way — MSG44, nothing else — because telling them apart tells whoever is presenting the
     * value how far they got.
     *
     * <p>The reuse branch is the one that matters. A token is retired the instant it is redeemed, so a
     * retired token presented again cannot be the legitimate client following the protocol; it means
     * the value existed in two places. There is no way to tell which of the two is in front of us, so
     * neither keeps the session: the whole family is revoked before the refusal is raised, and both
     * have to sign in with a password again.
     *
     * <p>The revocation commits in a transaction of its own ({@link FamilyRevoker}), and that is not
     * an optimisation. This method is transactional, so a revocation written in the same unit of work
     * as the exception that refuses the replay would be rolled back with it: the response would say
     * MSG44, the family would still be alive, and the holder of the copy would simply present the next
     * token. It is the same trap as BR-03's counter, and a test presents a retired token and then
     * checks that the sibling really stopped working.
     *
     * <p>"Remember me" survives rotation without a column of its own. The successor's window is read
     * off the token being retired: a token issued for longer than the ordinary refresh window was a
     * remembered sign-in, and its successor gets the same. Comparing windows rather than measuring one
     * exactly is what keeps this stable — seven days and thirty days are far apart, and the alternative
     * would drift by the microseconds between the two clock reads that produced the original row.
     *
     * @throws BusinessException {@code SESSION_EXPIRED} (MSG44)
     */
    @Transactional
    public IssuedSession refresh(String presentedToken) {
        Instant now = clock.instant();
        UserToken token = tokens.findByTokenHashAndTokenType(
                        refreshTokenFactory.digestOf(presentedToken), TokenType.REFRESH)
                .orElseThrow(() -> sessionExpired("no refresh token matches the presented value"));

        if (token.getUsedAt() != null) {
            int revoked = familyRevoker.revoke(token.getTokenFamilyId(), now);
            log.warn(
                    "Refresh token reuse detected for account {}: revoked {} tokens of family {}",
                    token.getUserId(),
                    revoked,
                    token.getTokenFamilyId());
            throw sessionExpired("a retired refresh token was presented again");
        }
        if (!token.isUsableAt(now)) {
            throw sessionExpired("the refresh token presented has expired");
        }

        UserSummary account = users.findForSessionRenewal(token.getUserId())
                .orElseThrow(() -> sessionExpired("account " + token.getUserId() + " may no longer hold a session"));

        token.markUsed(now);
        tokens.save(token);
        return issueSession(account, token.getTokenFamilyId(), successorWindowOf(token), now);
    }

    /**
     * Ends a session (SRS UC-05, section 3.2.3).
     *
     * <p>Revokes the presented refresh token and every other token of its family, so that no value
     * from this sign-in can open a new session. Logging out of one device therefore ends that device's
     * session and no other, because each sign-in starts a family of its own.
     *
     * <p><strong>Access tokens are not revoked, and cannot be.</strong> An access token is verified by
     * its signature and its expiry and is never looked up, which is what makes it cheap; the price is
     * that one already issued keeps working until it expires, at most fifteen minutes after this call.
     * What logging out guarantees is that no <em>new</em> access token can be obtained. A client that
     * wants the rest of that window closed discards its access token, which this API cannot do for it.
     *
     * <p>Answers the same whether the token was valid, already used, or never existed. A logout that
     * reported "that token was not valid" would be a way to test refresh tokens, and the caller has
     * nothing to do differently either way — the session is over.
     */
    @Transactional
    public void logout(String presentedToken) {
        Instant now = clock.instant();
        tokens.findByTokenHashAndTokenType(refreshTokenFactory.digestOf(presentedToken), TokenType.REFRESH)
                .ifPresent(token -> {
                    int revoked = familyRevoker.revoke(token.getTokenFamilyId(), now);
                    log.info("Closed a session for account {}: revoked {} tokens", token.getUserId(), revoked);
                });
    }

    /**
     * Writes the refresh token and builds the pair the caller receives.
     *
     * <p>Transactional at the caller's level rather than here: {@link #refresh} has to retire the old
     * token and insert the new one together, or a crash between the two would leave a client holding a
     * value that has been spent and no replacement.
     */
    private IssuedSession issueSession(UserSummary account, UUID family, Duration refreshWindow, Instant now) {
        String refreshToken = refreshTokenFactory.newToken();
        Instant refreshExpiresAt = now.plus(refreshWindow);
        tokens.save(UserToken.issueRefresh(
                account.userId(), refreshTokenFactory.digestOf(refreshToken), refreshExpiresAt, family));

        AccessTokenIssuer.IssuedAccessToken access =
                accessTokens.issueFor(account.userId(), account.role().name());
        return new IssuedSession(
                access.value(), access.expiresAt(), refreshToken, refreshExpiresAt, account.role(), account.userId());
    }

    /**
     * The window the successor of this token gets: thirty days when the sign-in asked to be
     * remembered, seven otherwise (SRS 3.2.3).
     *
     * <p>Read from the retired token's own window rather than from a stored flag, because there is no
     * column for one and because the window already records the answer. It is compared against the
     * ordinary window rather than measured, so that the microseconds between the two clock reads that
     * produced the original row cannot make a remembered session decay into an ordinary one after
     * enough rotations.
     */
    private Duration successorWindowOf(UserToken token) {
        Duration issuedFor = Duration.between(token.getCreatedAt(), token.getExpiresAt());
        return tokenProperties.refreshTokenTtl(issuedFor.compareTo(tokenProperties.refreshTokenTtl()) > 0);
    }

    /**
     * MSG44, for every way a session can turn out to be over.
     *
     * <p>The reason goes to the log and never into the response. Unknown, retired, expired and
     * "the account may no longer sign in" are four different facts about the value presented, and a
     * problem detail carries a sentence as well as a message code — four sentences would tell whoever
     * is holding a copied token exactly how far they got, which is the one thing the shared code
     * MSG44 exists to refuse. Every one of them answers the identical body.
     */
    private static BusinessException sessionExpired(String reason) {
        log.info("Session refused: {}", reason);
        return new BusinessException(ErrorCode.SESSION_EXPIRED, SESSION_OVER_DETAIL);
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

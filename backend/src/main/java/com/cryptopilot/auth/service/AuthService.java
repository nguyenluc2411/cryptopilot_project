package com.cryptopilot.auth.service;

import com.cryptopilot.auth.PasswordPolicy;
import com.cryptopilot.auth.model.IssuedSession;
import com.cryptopilot.common.exception.BusinessException;

/**
 * The use cases of {@link com.cryptopilot.auth.service.impl.AuthServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: BR-01, BR-02, BR-03, BR-05, BR-06; SRS UC-01, UC-02, UC-03, UC-05, sections 3.2.1, 3.2.2 and 3.2.3; messages MSG03, MSG04, MSG05, MSG06, MSG07, MSG08, MSG09, MSG10, MSG11, MSG44; TECHNICAL_DESIGN sections 5.3 and 7.15.
 */
public interface AuthService {

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
    void register(String email, String rawPassword, String displayName);

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
    void verifyEmail(String presentedToken);

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
     *
     * <p>What is identical is the <em>answer</em>, not the time it takes to give it, and here there are
     * three tiers rather than two: an unknown or already verified address returns after one query, a
     * throttled one after the throttle check, and only the third writes and publishes. That leaks not
     * just whether an address holds an unverified account but whether it has asked recently. {@link
     * #login} equalises this kind of difference deliberately; this path does not. A-29, with the same
     * fix and the same reason for not reaching for a constant delay.
     */
    void resendVerification(String email);

    /**
     * Signs an account in and opens a session (SRS UC-03, section 3.2.3).
     *
     * <p>The work is done in an order that keeps an endpoint taking an address from answering
     * questions about it. Every request costs one bcrypt verification, against the stored hash when an
     * account holds the address and against {@code hashOfNothing} when none does, so the two are not
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
    IssuedSession login(String email, String rawPassword, boolean rememberMe);

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
     * <p>The revocation commits in a transaction of its own ({@code FamilyRevoker}), and that is not
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
    IssuedSession refresh(String presentedToken);

    /**
     * Ends a session (SRS UC-05, section 3.2.3).
     *
     * <p>Revokes the presented refresh token and every other token of its family, so that no value
     * from this sign-in can open a new session. Logging out of one device therefore ends that device's
     * session and no other, because each sign-in starts a family of its own.
     *
     * <p><strong>The access token dies with the session.</strong> It names its session in the
     * {@code sid} claim, and once the family holds no unused refresh token the resource server refuses
     * it on the next request rather than at its expiry (D-33, {@link LiveSessions}). Only a token
     * issued before the claim existed outlives the session, for at most the fifteen minutes it had.
     *
     * <p>Answers the same whether the token was valid, already used, or never existed. A logout that
     * reported "that token was not valid" would be a way to test refresh tokens, and the caller has
     * nothing to do differently either way — the session is over.
     *
     * <p>When the mobile application names its messaging token, that installation stops receiving push
     * notifications in the same transaction (SRS 3.2.5). The account is the one the refresh token
     * belongs to, never one the request names, so a sign-out can silence only its own account's
     * device; a token the account does not hold is ignored as silently as an unknown refresh token.
     */
    void logout(String presentedToken, String fcmToken);

    /**
     * Creates a password reset link and announces it, for the address on SCR-05 (SRS UC-04, section
     * 3.2.4).
     *
     * <p>Answers nothing, and that is the whole design of it. SRS 3.2.4 says the system "always shows
     * MSG12", so an address nobody registered, an address whose account has never verified itself and
     * an address that is about to receive a link are three situations this method cannot be used to
     * tell apart. It returns {@code void} rather than a flag for that reason: there is no result for
     * a caller to leak.
     *
     * <p>Only a verified account gets a link, which SRS 3.2.4 states. An unverified address has not
     * been shown to belong to the person asking, so mailing a password-setting link to it would let
     * anyone who registered somebody else's address later take the account.
     *
     * <p>Status is not checked. A locked or banned account may hold a reset link and may spend it,
     * and still cannot sign in (BR-06). Refusing here would make the response depend on the account's
     * state, which is the question MSG12 exists to refuse.
     *
     * <p>What is identical is the <em>answer</em>, not the time it takes to give it: an address that
     * gets a link costs an update, an insert and an event that an unknown one does not, so the three
     * cases are still distinguishable by how long the request runs. {@link #login} equalises exactly
     * this, with a bcrypt verification against a hash of nothing, so the standard is the one this
     * class already sets elsewhere and this path does not meet. Recorded as A-29 rather than fixed
     * here, because the honest fix is not a sleep - a constant delay is a new guess at a number and
     * leaks under statistics anyway - but moving the work off the request, which is the mail module's
     * seam and does not exist yet.
     *
     * <p>The previous unused links are invalidated first. BR-04 makes each link single-use but says
     * nothing about a second request, and without this every request would leave another working link
     * behind in a mailbox - the same failure SRS 3.2.2 names for verification links, with a worse
     * consequence, so the same treatment.
     */
    void requestPasswordReset(String email);

    /**
     * Spends a reset link and sets the new password, ending every session of the account (SRS UC-04,
     * BR-04).
     *
     * <p>Three writes in one transaction, and the transaction is the point. The link is marked used,
     * the hash is replaced and every refresh token of the account is revoked; a crash between the
     * second and the third would leave the old password gone and the old sessions alive, which is
     * exactly the outcome BR-04's second sentence exists to prevent.
     *
     * <p>The password is checked before any of it. BR-02 is applied by {@link PasswordPolicy} rather
     * than only by the request record, so that a new entry point cannot forget it, and it runs first
     * so a request that was never going to succeed spends no link.
     *
     * <p>The lookup is by digest <em>and</em> kind, so a verification link cannot set a password.
     * Unknown, already used and expired all answer MSG07 and are indistinguishable on purpose. The
     * entity decides "used" and "expired"; this method supplies the instant.
     *
     * <p>Every refresh token is revoked, not one family: BR-04 says "all refresh tokens of the
     * account", and somebody resetting a password is frequently doing it because another session is
     * one they did not open. {@code invalidateUnused} over {@code REFRESH} is that bulk write -
     * revocation and expiry are the same column, which is what makes one statement enough.
     *
     * @throws BusinessException {@code PASSWORD_POLICY_VIOLATION} (MSG03) or
     *     {@code TOKEN_INVALID_OR_EXPIRED} (MSG07)
     */
    void resetPassword(String presentedToken, String rawPassword);
}

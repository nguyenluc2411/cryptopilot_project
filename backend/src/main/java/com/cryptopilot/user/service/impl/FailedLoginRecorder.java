package com.cryptopilot.user.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.user.entity.UserAccount;
import com.cryptopilot.user.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts a rejected sign-in attempt in a transaction of its own (BR-03).
 *
 * <h2>Why this is a class and not three lines in {@link UserService}</h2>
 *
 * <p>The write happens on the path that ends in a refusal, and a refusal is an exception. Put the
 * increment in the same transaction as the throw and the rollback undoes it: the counter never
 * advances, the fifth failure never arrives, and BR-03 is a rule that passes review and does nothing
 * — the lock never appears no matter how many passwords are tried. So the increment commits on its
 * own, before the exception is raised.
 *
 * <p>{@code REQUIRES_NEW} rather than relying on the caller having no transaction, because that is a
 * property of the caller and callers change. Suspending whatever is active and committing separately
 * is the same behaviour whether the sign-in is wrapped in a transaction today or a year from now,
 * which is what keeps this from being a bug that reappears.
 *
 * <p>And a separate bean rather than an annotated private step, because a {@code @Transactional}
 * method called from inside the same object goes through {@code this} and not through the proxy, so
 * the annotation does nothing at all. That failure is silent — the code reads as though it works —
 * which is why the separation is structural here and a test asserts the counter after a rejected
 * attempt rather than only after a successful one.
 *
 * <p>Rule: BR-03.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Unit of Work"; the work that must survive a failure cannot share the failing
 * unit).
 * <p>Reference: Grassi, P. A., Garcia, M. E. &amp; Fenton, J. L. (2017). NIST SP 800-63B, section
 * 5.2.2 (the count of consecutive failures is state the verifier keeps).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FailedLoginRecorder {

    private final UserAccountRepository accounts;

    /**
     * Records one rejected attempt against this account and commits it.
     *
     * <p>The account is loaded again inside this transaction rather than passed in: an entity read by
     * the caller belongs to the caller's persistence context, and writing it here would either change
     * nothing or write through a context this transaction does not own.
     *
     * <p>The row is locked for the length of this transaction, so concurrent wrong passwords for one
     * account are counted one after another and none is lost to a failed version check. An account
     * that is already locked when the lock is obtained (a concurrent attempt locked it) counts nothing
     * and is refused as locked, as it would have been had the attempts arrived in sequence. The caller
     * must not hold a connection of its own while it waits here, or concurrent attempts exhaust the
     * pool.
     *
     * <p>Answers the minutes MSG09 should name when the account is locked after this attempt, and
     * nothing when it is not. The number comes from the account rather than from the rule's constant:
     * the two are equal at the attempt that locks, but reading it from the entity is what keeps the
     * answer right if the lockout is ever computed from anything other than a fixed window, and it
     * keeps the duration a fact of the account rather than a number the caller knows.
     *
     * <p>Rule: BR-03.
     *
     * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly,
     * ch. 7 ("Preventing Lost Updates": explicit locking).
     * <p>Reference: OWASP Foundation. <i>Authentication Cheat Sheet</i> ("Account Lockout").
     *
     * @return the minutes still to wait, or empty when the account is not locked (BR-03)
     * @throws ResourceNotFoundException when no account has this identifier
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<Long> record(UUID userId, Instant at) {
        UserAccount account = accounts.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", userId));
        if (account.isLockedOutAt(at)) {
            return Optional.of(account.lockoutMinutesRemainingAt(at));
        }
        boolean nowLocked = account.recordFailedLogin(at);
        accounts.save(account);
        return nowLocked ? Optional.of(account.lockoutMinutesRemainingAt(at)) : Optional.empty();
    }
}

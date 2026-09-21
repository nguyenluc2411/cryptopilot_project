package com.cryptopilot.user.repository;

import com.cryptopilot.user.entity.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collection of accounts, expressed as the four operations the identity use cases actually
 * perform. One repository, because {@link UserAccount} is the aggregate root: the profile and the
 * devices are parts of that aggregate and the tokens belong to {@code auth}, so neither is reached
 * through here.
 *
 * <h2>Why this extends {@code Repository} and not {@code JpaRepository}</h2>
 *
 * <p>{@code JpaRepository} would contribute {@code findAll()}, {@code deleteAll()} and
 * {@code count()} to a table that grows with every registration. None of them has a caller, and
 * {@code findAll()} in particular is the method that looks harmless in a test and loads a hundred
 * thousand rows in production. Declaring the base interface and listing the four operations makes
 * the absence structural rather than a convention somebody has to remember; the administration
 * screens that really do list accounts (UC-42) will add a paged query, not an unbounded one.
 *
 * <h2>Why the two lookups are written out</h2>
 *
 * <p>Registration compares addresses case-insensitively, and the schema enforces that with a
 * <em>functional</em> unique index on {@code lower(email)}. Spring Data's derived
 * {@code IgnoreCase} keyword generates {@code upper(email) = upper(?)}, which no index in this
 * schema covers — the query would be correct and would read the whole table. The JPQL below says
 * {@code lower} explicitly so that the predicate matches the index that exists, and a test asserts
 * the plan actually chooses it rather than trusting that it does.
 *
 * <h2>Uniqueness</h2>
 *
 * <p>{@link #existsByEmailIgnoringCase(String)} is an optimisation, not the guarantee. Two
 * registrations for the same address can both pass it and both proceed; what stops the second is
 * {@code uq_user_account_email_lower}, which raises a constraint violation the web boundary
 * translates into a conflict response. Any caller that checks first must therefore still handle
 * the violation — the check exists to give the ordinary case a clean message, not to make the
 * race impossible.
 *
 * <h2>Transactions</h2>
 *
 * <p>The reads below are read-only, so Hibernate skips dirty checking and the connection is marked
 * read-only for the driver. The transaction that spans a write is the service's, not this
 * interface's: registration writes an account and a profile and must do both or neither, which a
 * per-method transaction here could not express. {@code save} therefore joins whatever transaction
 * the caller already opened.
 *
 * <p>Rule: BR-01 (an address identifies one account), BR-05; TECHNICAL_DESIGN sections 3.1 and 6.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 (a repository
 * is the illusion of a collection of aggregate roots, and only of roots).
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 12
 * (a repository interface offers the finders the use cases need and no more).
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley ("Repository"; "Unit of Work" for why the transaction is the caller's).
 */
public interface UserAccountRepository extends Repository<UserAccount, UUID> {

    /**
     * Whether an account already holds this address, compared without regard to case.
     *
     * <p>Answers existence, so it returns a boolean and not the row: a caller that receives an
     * entity from a question about existence ends up using it, and this method is allowed to be
     * stale by the time it returns.
     *
     * <p>Backed by {@code uq_user_account_email_lower}, the functional unique index on
     * {@code lower(email)}.
     *
     * <p>Rule: BR-01; SRS UC-01 (MSG04).
     */
    @Transactional(readOnly = true)
    @Query("select count(a) > 0 from UserAccount a where lower(a.email) = lower(:email)")
    boolean existsByEmailIgnoringCase(@Param("email") String email);

    /**
     * The account holding this address, compared without regard to case, or empty if there is
     * none. Empty rather than {@code null}, so that "no such account" is a value the caller has to
     * handle rather than a dereference that happens to work in the tested path.
     *
     * <p>Backed by {@code uq_user_account_email_lower}. The index is unique, so at most one row can
     * match and the single result needs no ordering to be deterministic.
     *
     * <p>Rule: BR-01; SRS UC-02, UC-03.
     */
    @Transactional(readOnly = true)
    @Query("select a from UserAccount a where lower(a.email) = lower(:email)")
    Optional<UserAccount> findByEmailIgnoringCase(@Param("email") String email);

    /** The account with this identifier, or empty. Backed by the primary key. */
    @Transactional(readOnly = true)
    Optional<UserAccount> findById(UUID id);

    /**
     * Writes an account, inserting it when it is new and updating it otherwise.
     *
     * <p>{@code UserAccount} assigns its own key, so the distinction cannot be made from the key
     * being null; {@code BaseEntity} implements {@code Persistable} and answers it from the
     * creation instant, which is what keeps a first save from selecting the row it is about to
     * insert.
     *
     * <p>Not transactional here on purpose: the caller's transaction is the unit of work.
     */
    UserAccount save(UserAccount account);

    /**
     * Sends everything written so far to the database, without ending the transaction.
     *
     * <p>Registration needs this and would be wrong without it. {@code UserAccount} assigns its own
     * key, so {@code save} only makes the entity persistent — no statement reaches the database
     * until the transaction flushes, which by default is at commit. The unique index on
     * {@code lower(email)} is therefore consulted after the service that wanted to catch its
     * refusal has already returned, and the duplicate that loses a race would be answered with the
     * generic conflict instead of MSG04. Flushing where the insert is expected puts the refusal
     * inside the {@code try} that knows what it means.
     *
     * <p>Not transactional here, for the same reason {@code save} is not: the unit of work belongs
     * to the caller, and this only decides when a statement is sent inside it.
     *
     * <p>Rule: SRS UC-01 (MSG04).
     *
     * <p>Reference: Bauer, C., King, G. &amp; Gregory, G. (2015). <i>Java Persistence with
     * Hibernate</i> (2nd ed.). Manning, ch. 10 (flush timing decides when a constraint is checked).
     */
    void flush();
}

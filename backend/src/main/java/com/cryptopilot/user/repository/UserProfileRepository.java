package com.cryptopilot.user.repository;

import com.cryptopilot.user.entity.UserProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to a profile row.
 *
 * <h2>This is an exception to a standing rule, and it is one on purpose</h2>
 *
 * <p>A profile is not an aggregate root — it is part of the account aggregate — and the design says
 * a repository belongs to a root. It gets one anyway, because the two other ways of reaching the
 * row were worse:
 *
 * <ul>
 *   <li>A cascading {@code @OneToOne} from the account would have reversed the decision that no JPA
 *       association exists in either direction. That decision is load-bearing across every entity
 *       written so far, it is what keeps a query over accounts from becoming a query per account,
 *       and reversing it for one table would make it a convention rather than a rule.
 *   <li>Persisting through the {@code EntityManager} inside the service would have kept both rules
 *       intact and put two persistence idioms in one class — a repository for the account, a
 *       manager for its profile — and the profile still has to be <em>loaded</em> when a trader
 *       edits it, so the manager would have spread rather than stayed in one method.
 * </ul>
 *
 * <p>What it costs is stated rather than hidden: "a repository per aggregate root" now has one
 * documented exception, and the reason it is safe here is that the exception is forced by the
 * absence of an association — with no way to navigate to the row, a row with its own table and its
 * own key needs its own gateway or it cannot be reached at all. The rule still stands for anything
 * that can be reached through its root.
 *
 * <p>It offers no {@code findAll} for the same reason the account repository does not: the table
 * has one row per account.
 *
 * <p>Rule: SRS UC-01, UC-06; TECHNICAL_DESIGN sections 3.1 and 6.
 *
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 12
 * (a repository serves an aggregate root; the exceptions are deliberate and few).
 */
public interface UserProfileRepository extends Repository<UserProfile, UUID> {

    /**
     * The profile of an account, or empty. Keyed by the account's identifier, because the primary
     * key of the profile table <em>is</em> the foreign key to the account.
     */
    @Transactional(readOnly = true)
    Optional<UserProfile> findById(UUID userId);

    /**
     * Writes a profile.
     *
     * <p>Not transactional here: a profile is created in the same transaction as the account it
     * belongs to, and that transaction is the service's.
     */
    UserProfile save(UserProfile profile);
}

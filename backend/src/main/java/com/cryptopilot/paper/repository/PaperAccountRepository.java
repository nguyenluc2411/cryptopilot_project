package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to paper accounts, looked up by their owner: one account per Trader.
 *
 * <p>Rule: TR-04.
 */
public interface PaperAccountRepository extends Repository<PaperAccount, UUID> {

    @Transactional(readOnly = true)
    Optional<PaperAccount> findByUserId(UUID userId);

    /** An account by its key: for the matching engine, which knows the account of an order and not its Trader. */
    @Transactional(readOnly = true)
    Optional<PaperAccount> findById(UUID accountId);

    /** Writes an account. Not transactional here; the unit of work is the calling service's. */
    PaperAccount save(PaperAccount account);

    /** Sends pending statements, so {@code uq_paper_account_user} answers where the caller can read it. */
    void flush();
}

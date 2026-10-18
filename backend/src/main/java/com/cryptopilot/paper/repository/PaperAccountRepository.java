package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
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

    /** The id of every paper account without a snapshot of the day, for the daily equity snapshot (TR-13; PT-R02). */
    @Transactional(readOnly = true)
    @Query("""
            select a.id from PaperAccount a
             where not exists (select 1 from PaperEquitySnapshot s where s.accountId = a.id and s.snapshotDate = :day)
             order by a.id""")
    List<UUID> findIdsWithoutSnapshotOn(LocalDate day);

    /** Sends pending statements, so {@code uq_paper_account_user} answers where the caller can read it. */
    void flush();
}

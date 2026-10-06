package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the ledger of paper wallets: written once per balance change, read as the transaction history.
 *
 * <p>Rule: TR-04.
 */
public interface PaperLedgerEntryRepository extends Repository<PaperLedgerEntry, UUID> {

    /**
     * One page of an account's entries in the wallets and types, created in {@code [from, to)}, newest first. No
     * parameter is null, so PostgreSQL can type every one.
     */
    @Transactional(readOnly = true)
    @Query(
            value =
                    "select e from PaperLedgerEntry e where e.accountId = :accountId and e.walletType in :wallets"
                            + " and e.entryType in :types and e.createdAt >= :from and e.createdAt < :to order by e.createdAt desc, e.id desc",
            countQuery = "select count(e) from PaperLedgerEntry e where e.accountId = :accountId"
                    + " and e.walletType in :wallets and e.entryType in :types"
                    + " and e.createdAt >= :from and e.createdAt < :to")
    Page<PaperLedgerEntry> search(
            UUID accountId,
            Collection<WalletType> wallets,
            Collection<LedgerEntryType> types,
            Instant from,
            Instant to,
            Pageable pageable);

    /** Writes an entry. Not transactional here; the unit of work is the calling service's. */
    PaperLedgerEntry save(PaperLedgerEntry entry);
}

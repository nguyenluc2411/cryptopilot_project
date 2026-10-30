package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
                    "select e from PaperLedgerEntry e where e.userId = :userId and e.walletType in :wallets"
                            + " and e.entryType in :types and e.createdAt >= :from and e.createdAt < :to order by e.createdAt desc, e.id desc",
            countQuery = "select count(e) from PaperLedgerEntry e where e.userId = :userId"
                    + " and e.walletType in :wallets and e.entryType in :types"
                    + " and e.createdAt >= :from and e.createdAt < :to")
    Page<PaperLedgerEntry> search(
            UUID userId,
            Collection<WalletType> wallets,
            Collection<LedgerEntryType> types,
            Instant from,
            Instant to,
            Pageable pageable);

    /** Whether the wallet's coin has an entry of another type than {@code type}. */
    @Transactional(readOnly = true)
    boolean existsByUserIdAndWalletTypeAndCoinIdAndEntryTypeNot(
            UUID userId, WalletType walletType, UUID coinId, LedgerEntryType type);

    /**
     * One page of the Trader's transfers between wallets, as the pageable orders them: each is its debit entry, which
     * names the wallet it left and the amount it moved (negated).
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select e from PaperLedgerEntry e where e.userId = :userId"
                    + " and e.entryType = com.cryptopilot.paper.model.enums.LedgerEntryType.TRANSFER and e.amount < 0",
            countQuery = "select count(e) from PaperLedgerEntry e where e.userId = :userId"
                    + " and e.entryType = com.cryptopilot.paper.model.enums.LedgerEntryType.TRANSFER and e.amount < 0")
    Page<PaperLedgerEntry> findTransfers(UUID userId, Pageable pageable);

    /** The debit entry of the Trader's transfer with this idempotency key, or empty. */
    @Transactional(readOnly = true)
    @Query("select e from PaperLedgerEntry e where e.userId = :userId and e.clientTransferId = :clientTransferId"
            + " and e.entryType = com.cryptopilot.paper.model.enums.LedgerEntryType.TRANSFER and e.amount < 0")
    Optional<PaperLedgerEntry> findTransfer(UUID userId, String clientTransferId);

    /** Writes an entry. Not transactional here; the unit of work is the calling service's. */
    /**
     * The Trader's Futures wallet entries of these types written in {@code [from, to)}, newest first (TR-19). No
     * parameter is null.
     */
    @Transactional(readOnly = true)
    @Query("select e from PaperLedgerEntry e where e.userId = :userId"
            + " and e.walletType = com.cryptopilot.paper.model.enums.WalletType.FUTURES and e.entryType in :types"
            + " and e.createdAt >= :from and e.createdAt < :to order by e.createdAt desc, e.id desc")
    List<PaperLedgerEntry> searchFutures(
            UUID userId, Collection<LedgerEntryType> types, Instant from, Instant to, Pageable pageable);

    /** As {@link #searchFutures}, for the entries of one pair. */
    @Transactional(readOnly = true)
    @Query("select e from PaperLedgerEntry e where e.userId = :userId and e.pairId = :pairId"
            + " and e.walletType = com.cryptopilot.paper.model.enums.WalletType.FUTURES and e.entryType in :types"
            + " and e.createdAt >= :from and e.createdAt < :to order by e.createdAt desc, e.id desc")
    List<PaperLedgerEntry> searchFuturesPair(
            UUID userId, UUID pairId, Collection<LedgerEntryType> types, Instant from, Instant to, Pageable pageable);

    /** Whether an entry of this type points at this reference: a funding fee already written (TR-24). */
    boolean existsByRefTypeAndRefIdAndEntryType(LedgerRefType refType, UUID refId, LedgerEntryType entryType);

    PaperLedgerEntry save(PaperLedgerEntry entry);

    /**
     * Sends pending statements: the opening reads its grant back through plain SQL, and a second grant of the same
     * coin answers {@code uq_wallet_ledger_entry_grant} where the caller can read it.
     */
    void flush();
}

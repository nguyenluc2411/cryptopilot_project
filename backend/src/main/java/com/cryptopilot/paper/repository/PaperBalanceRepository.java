package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the balances of paper wallets. Every lookup names the account, so another Trader's balance is never
 * loaded.
 *
 * <p>Rule: TR-04.
 */
public interface PaperBalanceRepository extends Repository<PaperBalance, UUID> {

    /** The balances of one wallet, in no particular order. */
    @Transactional(readOnly = true)
    List<PaperBalance> findByUserIdAndWalletType(UUID userId, WalletType walletType);

    /** The balances of both wallets of an account. */
    @Transactional(readOnly = true)
    List<PaperBalance> findByUserId(UUID userId);

    /** One coin of one wallet, or empty when the wallet has never held it. */
    @Transactional(readOnly = true)
    Optional<PaperBalance> findByUserIdAndWalletTypeAndCoinId(UUID userId, WalletType walletType, UUID coinId);

    /** Several coins of one wallet, in one query; a coin the wallet has never held is left out. */
    @Transactional(readOnly = true)
    List<PaperBalance> findByUserIdAndWalletTypeAndCoinIdIn(
            UUID userId, WalletType walletType, Collection<UUID> coinIds);

    /**
     * The balances of a wallet held, not zero, whose cost is not known and whose only ledger entries are of
     * {@code onlyType}: the granted coins that had no price when the account opened (PT-R13).
     */
    @Transactional(readOnly = true)
    @Query("""
            select b from PaperBalance b
             where b.walletType = :wallet and b.costBasisAmount is null and b.freeAmount + b.lockedAmount > 0
               and not exists (select 1 from PaperLedgerEntry e
                                where e.userId = b.userId and e.walletType = b.walletType
                                  and e.coinId = b.coinId and e.entryType <> :onlyType)
             order by b.userId, b.coinId""")
    List<PaperBalance> findWithoutCostChangedOnlyBy(WalletType wallet, LedgerEntryType onlyType);

    /** Writes a balance. Not transactional here; the unit of work is the calling service's. */
    PaperBalance save(PaperBalance balance);
}

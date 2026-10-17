package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.model.enums.WalletType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    List<PaperBalance> findByAccountIdAndWalletType(UUID accountId, WalletType walletType);

    /** The balances of both wallets of an account. */
    @Transactional(readOnly = true)
    List<PaperBalance> findByAccountId(UUID accountId);

    /** One coin of one wallet, or empty when the wallet has never held it. */
    @Transactional(readOnly = true)
    Optional<PaperBalance> findByAccountIdAndWalletTypeAndCoinId(UUID accountId, WalletType walletType, UUID coinId);

    /** Several coins of one wallet, in one query; a coin the wallet has never held is left out. */
    @Transactional(readOnly = true)
    List<PaperBalance> findByAccountIdAndWalletTypeAndCoinIdIn(
            UUID accountId, WalletType walletType, Collection<UUID> coinIds);

    /** Writes a balance. Not transactional here; the unit of work is the calling service's. */
    PaperBalance save(PaperBalance balance);
}

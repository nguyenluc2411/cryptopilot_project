package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.paper.config.PaperOrderProperties;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperFill.Execution;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperFillRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles one execution of a Spot order in the Spot wallet: the coin paid goes out, the coin bought comes in,
 * and the commission is taken from the coin that came in, as Binance charges an account that pays no fee in BNB. Each
 * change is a ledger entry pointing at the fill, so the trade history and the transaction history agree.
 *
 * <ul>
 *   <li>A buy pays its price times its quantity rounded up to the amount scale, and a sale brings it rounded down
 *       ({@link PaperOrder#buyCost}, {@link PaperOrder#saleProceeds}): the wallet never gains from a rounding.
 *   <li>The commission is the fee rate of the fill's liquidity times what came in, rounded up to the amount scale.
 *   <li>A LIMIT order pays from what it locked, and its lock shrinks to what the rest of it needs
 *       ({@link PaperOrder#lockedAfter}); a buy that executes below its price gets the difference back at once. A buy
 *       at its price pays what its lock shrinks by, so all its executions together cost what one would have, never a
 *       rounding more. A MARKET order pays from what is free, and is refused when too little is.
 * </ul>
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's paper lock and has checked that the order works.
 *
 * <p>Rule: TR-02, TR-04; BR-21 (Spot owns what it sells).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SpotSettlement {

    private final PaperWallets wallets;
    private final PaperFillRepository fills;
    private final PaperOrderProperties fees;

    /**
     * Executes {@code quantity} of the order at {@code price} and records the fill.
     *
     * @param quantity the base quantity, at most what the order has left
     * @param fromLocked whether the order pays from what it locked when it was placed (a LIMIT order)
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when an order
     *     that pays from what is free finds too little; the caller's transaction then rolls back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperFill execute(
            PaperAccount account,
            PaperOrder order,
            PairCoins coins,
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant tradedAt,
            boolean fromLocked) {
        wallets.preload(account, WalletType.SPOT, coins.base(), coins.quote());
        boolean buy = order.getSide() == OrderSide.BUY;
        BigDecimal released = BigDecimal.ZERO;
        BigDecimal quoteAmount = buy ? PaperOrder.buyCost(price, quantity) : PaperOrder.saleProceeds(price, quantity);
        if (fromLocked && buy) {
            BigDecimal freed = order.lockedAmount().subtract(order.lockedAfter(quantity));
            quoteAmount = quoteAmount.min(freed);
            released = freed.subtract(quoteAmount);
        }
        CoinListing paid = buy ? coins.quote() : coins.base();
        CoinListing received = buy ? coins.base() : coins.quote();
        BigDecimal paidAmount = buy ? quoteAmount : quantity;
        BigDecimal receivedAmount = buy ? quantity : quoteAmount;
        BigDecimal fee = receivedAmount
                .multiply(liquidity == Liquidity.MAKER ? fees.spotMakerFeeRate() : fees.spotTakerFeeRate())
                .setScale(Rounding.AMOUNT_SCALE, RoundingMode.UP);

        PaperFill fill = PaperFill.of(
                order,
                new Execution(price, quantity, quoteAmount, fee, received.coinId(), liquidity, source, tradedAt));
        Ref ref = Ref.to(LedgerRefType.FILL, fill.getId());
        if (fromLocked) {
            wallets.spendLocked(account, WalletType.SPOT, paid, paidAmount, LedgerEntryType.TRADE, ref);
            if (released.signum() > 0) {
                wallets.unlock(account, WalletType.SPOT, paid, released);
            }
        } else {
            wallets.debit(account, WalletType.SPOT, paid, paidAmount, LedgerEntryType.TRADE, ref);
        }
        wallets.credit(account, WalletType.SPOT, received, receivedAmount, LedgerEntryType.TRADE, ref);
        if (fee.signum() > 0) {
            wallets.debit(account, WalletType.SPOT, received, fee, LedgerEntryType.FEE, ref);
        }
        order.fill(quantity, price, quoteAmount, tradedAt);
        return fills.save(fill);
    }
}

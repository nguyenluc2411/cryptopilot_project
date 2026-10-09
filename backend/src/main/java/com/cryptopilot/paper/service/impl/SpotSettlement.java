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
 * Settles the execution of a whole Spot order in the Spot wallet: the coin paid goes out, the coin bought comes in,
 * and the commission is taken from the coin that came in, as Binance charges an account that pays no fee in BNB. Each
 * change is a ledger entry pointing at the fill, so the trade history and the transaction history agree.
 *
 * <ul>
 *   <li>A buy pays its price times its quantity rounded up to the amount scale, and a sale brings it rounded down
 *       ({@link PaperOrder#buyCost}, {@link PaperOrder#saleProceeds}): the wallet never gains from a rounding.
 *   <li>The commission is the fee rate of the fill's liquidity times what came in, rounded up to the amount scale.
 *   <li>A working LIMIT order pays from what it locked; a buy that executes below its price gets the rest back. An
 *       order that executes on arrival pays from what is free, and is refused when too little is.
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
     * Executes the whole order at {@code price} and records the fill.
     *
     * @param quantity the base quantity; the order's own, or for a MARKET buy by total what its amount buys
     * @param fromLocked whether the order pays from what it locked when it was placed (a working LIMIT order)
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
        BigDecimal quoteAmount = buy ? PaperOrder.buyCost(price, quantity) : PaperOrder.saleProceeds(price, quantity);
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
            BigDecimal locked = order.lockedAmount();
            wallets.spendLocked(account, WalletType.SPOT, paid, paidAmount, LedgerEntryType.TRADE, ref);
            BigDecimal left = locked.subtract(paidAmount);
            if (left.signum() > 0) {
                wallets.unlock(account, WalletType.SPOT, paid, left);
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

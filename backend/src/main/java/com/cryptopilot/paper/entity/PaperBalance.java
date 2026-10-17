package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.paper.model.enums.WalletType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * What one wallet of a paper account holds of one coin: the free amount, and the amount locked by open orders.
 * Neither goes below zero (the checks of V21 hold it too); an operation that would take it there is refused before
 * anything changes.
 *
 * <p>Only {@code PaperWallets} changes a balance, and it writes the ledger entry of every change in the same
 * transaction, so a balance always equals the sum of its entries.
 *
 * <p>Rule: TR-04.
 */
@Getter
@Entity
@Table(name = "paper_balance")
@AttributeOverride(name = "id", column = @Column(name = "balance_id", nullable = false, updatable = false))
public class PaperBalance extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false, length = 32, updatable = false)
    private WalletType walletType;

    @Column(name = "coin_id", nullable = false, updatable = false)
    private UUID coinId;

    @Column(name = "free_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal freeAmount;

    @Column(name = "locked_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal lockedAmount;

    /** For JPA only. */
    protected PaperBalance() {}

    private PaperBalance(UUID accountId, WalletType walletType, UUID coinId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.walletType = Objects.requireNonNull(walletType, "walletType must not be null");
        this.coinId = Objects.requireNonNull(coinId, "coinId must not be null");
        this.freeAmount = BigDecimal.ZERO;
        this.lockedAmount = BigDecimal.ZERO;
    }

    /**
     * What the holding cost, in USDT (TR-13): what the buys paid less the share of it the sales took away, the
     * opening grant at its price then. {@code null} when not known.
     */
    @Column(name = "cost_basis_amount", precision = 28, scale = 8)
    private BigDecimal costBasisAmount;

    /** An empty balance of a coin the wallet has not held before. */
    public static PaperBalance empty(UUID accountId, WalletType walletType, UUID coinId) {
        return new PaperBalance(accountId, walletType, coinId);
    }

    /** Free plus locked. */
    public BigDecimal total() {
        return freeAmount.add(lockedAmount);
    }

    /** Adds to the free amount. */
    public void credit(BigDecimal amount) {
        freeAmount = freeAmount.add(positive(amount));
    }

    /**
     * Takes from the free amount.
     *
     * @param asset the coin's symbol, which the refusal names to the Trader
     * @throws BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when less is free
     */
    public void debit(BigDecimal amount, String asset) {
        BigDecimal taken = positive(amount);
        requireFree(taken, asset);
        freeAmount = freeAmount.subtract(taken);
    }

    /**
     * Moves an amount from free to locked, for an order that waits.
     *
     * @param asset the coin's symbol, which the refusal names to the Trader
     * @throws BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when less is free
     */
    public void lock(BigDecimal amount, String asset) {
        BigDecimal locked = positive(amount);
        requireFree(locked, asset);
        freeAmount = freeAmount.subtract(locked);
        lockedAmount = lockedAmount.add(locked);
    }

    /** Moves an amount back from locked to free, for an order cancelled or filled for less. */
    public void unlock(BigDecimal amount) {
        BigDecimal released = positive(amount);
        requireLocked(released);
        lockedAmount = lockedAmount.subtract(released);
        freeAmount = freeAmount.add(released);
    }

    /** Takes from the locked amount, for an order that filled. */
    public void spendLocked(BigDecimal amount) {
        BigDecimal spent = positive(amount);
        requireLocked(spent);
        lockedAmount = lockedAmount.subtract(spent);
    }

    /**
     * Adds what an acquisition of the coin cost, in USDT. An empty balance starts its cost from it; a holding whose
     * cost is not known stays not known, so a part of it is never averaged with a guess.
     *
     * @param totalBefore the total held before the acquisition was credited
     */
    public void addCost(BigDecimal cost, BigDecimal totalBefore) {
        Objects.requireNonNull(cost, "cost must not be null");
        if (cost.signum() < 0) {
            throw new IllegalArgumentException("cost must not be negative, was " + cost.toPlainString());
        }
        if (totalBefore.signum() == 0) {
            costBasisAmount = scaled(cost);
        } else if (costBasisAmount != null) {
            costBasisAmount = scaled(costBasisAmount.add(cost));
        }
    }

    /**
     * Takes away the share of the cost a sale of {@code sold} took, the average cost method: what is left keeps its
     * average cost. Called once the sale has been taken from the balance; nothing left, nothing is left of the cost.
     */
    public void reduceCost(BigDecimal sold) {
        if (costBasisAmount == null) {
            return;
        }
        BigDecimal left = total();
        BigDecimal before = left.add(positive(sold));
        costBasisAmount = left.signum() == 0
                ? scaled(BigDecimal.ZERO)
                : scaled(costBasisAmount.multiply(left).divide(before, MathContext.DECIMAL128));
    }

    /** The average cost of one unit in USDT, or {@code null} when the cost is not known or nothing is held. */
    public BigDecimal averageCost() {
        BigDecimal total = total();
        if (costBasisAmount == null || total.signum() == 0) {
            return null;
        }
        return costBasisAmount.divide(total, 12, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(8, RoundingMode.HALF_EVEN);
    }

    /** The refusal names the coin by its symbol, then the amount needed and the amount free (message arguments). */
    private void requireFree(BigDecimal amount, String asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        if (freeAmount.compareTo(amount) < 0) {
            throw new BusinessException(
                    ErrorCode.PAPER_INSUFFICIENT_BALANCE,
                    walletType + " " + asset + " balance " + getId() + " has " + freeAmount.toPlainString() + " free, "
                            + amount.toPlainString() + " needed",
                    asset,
                    amount.toPlainString(),
                    freeAmount.toPlainString());
        }
    }

    // Locked amounts are moved by the application alone, so asking for more than is locked is a defect.
    private void requireLocked(BigDecimal amount) {
        if (lockedAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("balance " + getId() + " has " + lockedAmount.toPlainString() + " locked, "
                    + amount.toPlainString() + " asked");
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount.toPlainString());
        }
        return amount;
    }
}

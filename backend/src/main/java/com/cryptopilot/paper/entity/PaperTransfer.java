package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.WalletType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A move of one coin between the Spot and Futures wallets of a paper account: the transfer history of the wallet
 * screens. The two balance changes it makes are ledger entries that point back at it.
 *
 * <p>Rule: TR-04.
 */
@Getter
@Entity
@Table(name = "paper_transfer")
@AttributeOverride(name = "id", column = @Column(name = "transfer_id", nullable = false, updatable = false))
public class PaperTransfer extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /** The idempotency key the client sent, or one generated for a request that sent none. */
    @Column(name = "client_transfer_id", nullable = false, length = 64, updatable = false)
    private String clientTransferId;

    @Column(name = "coin_id", nullable = false, updatable = false)
    private UUID coinId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_wallet", nullable = false, length = 32, updatable = false)
    private WalletType fromWallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_wallet", nullable = false, length = 32, updatable = false)
    private WalletType toWallet;

    @Column(name = "amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal amount;

    /** For JPA only. */
    protected PaperTransfer() {}

    private PaperTransfer(UUID accountId, String clientTransferId, UUID coinId, WalletType from, BigDecimal amount) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.clientTransferId = Objects.requireNonNull(clientTransferId, "clientTransferId must not be null");
        this.coinId = Objects.requireNonNull(coinId, "coinId must not be null");
        this.fromWallet = Objects.requireNonNull(from, "from must not be null");
        this.toWallet = from == WalletType.SPOT ? WalletType.FUTURES : WalletType.SPOT;
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("a transfer moves a positive amount, not " + amount.toPlainString());
        }
    }

    /** A transfer out of {@code from} into the other wallet, known to the client by {@code clientTransferId}. */
    public static PaperTransfer out(
            UUID accountId, String clientTransferId, UUID coinId, WalletType from, BigDecimal amount) {
        return new PaperTransfer(accountId, clientTransferId, coinId, from, amount);
    }

    /** Whether a request sent with this transfer's key asks for this same transfer. */
    public boolean sameAs(UUID otherCoinId, WalletType otherFrom, BigDecimal otherAmount) {
        return coinId.equals(otherCoinId) && fromWallet == otherFrom && amount.compareTo(otherAmount) == 0;
    }
}

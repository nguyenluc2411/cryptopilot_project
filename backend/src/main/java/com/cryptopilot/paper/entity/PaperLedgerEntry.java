package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
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
 * One change of a paper balance, never edited and never deleted: the signed amount, the balance it left behind and
 * what caused it. The entries of a balance add up to it, which is what the transaction history of the trading screens
 * reads (Futures "Transaction history", the wallet history of Spot).
 *
 * <p>Rule: TR-04.
 *
 * <p>Reference: Fowler, M. (1997). <i>Analysis Patterns</i>. Addison-Wesley, ch. 6 "Inventory and Accounting"
 * (an account's balance is the sum of its entries; entries are never changed, only added).
 */
@Getter
@Entity
@Table(name = "paper_ledger_entry")
@AttributeOverride(name = "id", column = @Column(name = "entry_id", nullable = false, updatable = false))
public class PaperLedgerEntry extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false, length = 32, updatable = false)
    private WalletType walletType;

    @Column(name = "coin_id", nullable = false, updatable = false)
    private UUID coinId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32, updatable = false)
    private LedgerEntryType entryType;

    /** Positive when the balance grew, negative when it shrank; never zero. */
    @Column(name = "amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal amount;

    /** The total of the balance (free plus locked) after this entry. */
    @Column(name = "balance_after_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal balanceAfterAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", length = 32, updatable = false)
    private LedgerRefType refType;

    @Column(name = "ref_id", updatable = false)
    private UUID refId;

    /** For JPA only. */
    protected PaperLedgerEntry() {}

    private PaperLedgerEntry(PaperBalance balance, LedgerEntryType entryType, BigDecimal amount, Ref ref) {
        this.accountId = balance.getAccountId();
        this.walletType = balance.getWalletType();
        this.coinId = balance.getCoinId();
        this.entryType = Objects.requireNonNull(entryType, "entryType must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("a ledger entry changes the balance; the amount must not be zero");
        }
        this.balanceAfterAmount = balance.total();
        Ref cause = ref == null ? Ref.NONE : ref;
        this.refType = cause.type();
        this.refId = cause.id();
    }

    /**
     * The entry of a change already applied to {@code balance}.
     *
     * @param balance the balance after the change
     * @param entryType what changed it
     * @param amount the signed change of its total
     * @param ref the row that caused it, or {@code null} for none
     */
    public static PaperLedgerEntry of(PaperBalance balance, LedgerEntryType entryType, BigDecimal amount, Ref ref) {
        Objects.requireNonNull(balance, "balance must not be null");
        return new PaperLedgerEntry(balance, entryType, amount, ref);
    }

    /**
     * The row a ledger entry points at: both parts or neither, as {@code ck_paper_ledger_entry_ref} requires.
     *
     * @param type the kind of row
     * @param id its key
     */
    public record Ref(LedgerRefType type, UUID id) {

        /** No cause recorded. */
        public static final Ref NONE = new Ref(null, null);

        public Ref {
            if ((type == null) != (id == null)) {
                throw new IllegalArgumentException("a ledger reference names both its type and its id, or neither");
            }
        }

        public static Ref to(LedgerRefType type, UUID id) {
            return new Ref(Objects.requireNonNull(type, "type"), Objects.requireNonNull(id, "id"));
        }
    }
}

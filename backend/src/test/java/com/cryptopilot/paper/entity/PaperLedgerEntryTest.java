package com.cryptopilot.paper.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A ledger entry belongs to the Trader of its balance, and a transfer is the pair of entries that share its id and its
 * idempotency key: only a TRANSFER entry carries one, as {@code ck_wallet_ledger_entry_client_transfer_id} requires.
 *
 * <p>Rule: TR-04; D-117.
 */
class PaperLedgerEntryTest {

    private final UUID trader = UUID.randomUUID();
    private final PaperBalance balance = PaperBalance.empty(trader, WalletType.SPOT, UUID.randomUUID());

    @Test
    void anEntry_belongsToTheTraderOfItsBalance() {
        balance.credit(new BigDecimal("100"));

        PaperLedgerEntry entry = PaperLedgerEntry.of(
                balance, LedgerEntryType.INITIAL_GRANT, new BigDecimal("100"), Ref.to(LedgerRefType.USER, trader));

        assertThat(entry.getUserId()).isEqualTo(trader);
        assertThat(entry.getRefType()).isEqualTo(LedgerRefType.USER);
        assertThat(entry.getRefId()).isEqualTo(trader);
        assertThat(entry.getClientTransferId()).isNull();
    }

    @Test
    void aTransferEntry_carriesTheTransfersIdAndKey() {
        balance.credit(new BigDecimal("100"));
        UUID transfer = UUID.randomUUID();

        PaperLedgerEntry entry = PaperLedgerEntry.of(
                balance, LedgerEntryType.TRANSFER, new BigDecimal("-40"), Ref.transfer(transfer, "key-1"));

        assertThat(entry.getRefType()).isEqualTo(LedgerRefType.TRANSFER);
        assertThat(entry.getRefId()).isEqualTo(transfer);
        assertThat(entry.getClientTransferId()).isEqualTo("key-1");
    }

    @Test
    void aTransferEntry_withoutItsTransfer_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaperLedgerEntry.of(
                        balance,
                        LedgerEntryType.TRANSFER,
                        BigDecimal.ONE,
                        Ref.to(LedgerRefType.FILL, UUID.randomUUID())));
    }

    @Test
    void anotherEntry_pointingAtATransfer_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PaperLedgerEntry.of(
                        balance, LedgerEntryType.TRADE, BigDecimal.ONE, Ref.transfer(UUID.randomUUID(), "key-1")));
    }

    @Test
    void aReference_namesATransfersKeyExactlyWhenItIsATransfer() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Ref(LedgerRefType.TRANSFER, UUID.randomUUID(), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ref(LedgerRefType.FILL, UUID.randomUUID(), "key-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Ref(null, null, "key-1"));
        assertThatNullPointerException().isThrownBy(() -> Ref.transfer(UUID.randomUUID(), null));
        assertThat(Ref.NONE.clientTransferId()).isNull();
    }
}

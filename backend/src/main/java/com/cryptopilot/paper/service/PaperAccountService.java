package com.cryptopilot.paper.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.paper.dto.request.TransferRequest;
import com.cryptopilot.paper.dto.response.LedgerEntryResponse;
import com.cryptopilot.paper.dto.response.PaperAccountResponse;
import com.cryptopilot.paper.dto.response.TransferResponse;
import com.cryptopilot.paper.dto.response.WalletResponse;
import com.cryptopilot.paper.model.LedgerQuery;
import com.cryptopilot.paper.model.OpenedAccount;
import com.cryptopilot.paper.model.RecordedTransfer;
import com.cryptopilot.paper.model.enums.WalletType;
import java.util.UUID;

/**
 * The paper account of a Trader and its two wallets (TR-04).
 *
 * <p>An account is opened explicitly, by {@link #open}, when the Trader enters the trading screens; it is granted the
 * configured virtual funds then and never again (Q-T5). Every read is a pure read: before the account is opened, it
 * answers "not found" (MSG41) rather than opening one, so a crawler, a prefetch or a cache in front of a GET can cause
 * nothing.
 *
 * <p>Rule: TR-04; Q-T5.
 */
public interface PaperAccountService {

    /** Opens the caller's account with the configured funds, or returns it when it is open already. Idempotent. */
    OpenedAccount open(UUID userId);

    /**
     * The caller's account.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when it is not opened yet
     */
    PaperAccountResponse account(UUID userId);

    /** One wallet of the caller's account, valued at the current Spot prices; MSG41 when not opened. */
    WalletResponse wallet(UUID userId, WalletType wallet);

    /**
     * Moves USDT from one wallet to the other, once per idempotency key: a request sent again with the key of a
     * transfer already made returns that transfer and moves nothing.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the account is not opened yet
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when the wallet
     *     holds less free
     * @throws com.cryptopilot.common.exception.FieldValidationException when the coin is not USDT, the only coin that
     *     moves between the wallets
     * @throws com.cryptopilot.common.exception.BusinessException {@code DATA_CONFLICT} when the idempotency key was
     *     used for a transfer of other values
     */
    RecordedTransfer transfer(UUID userId, TransferRequest request);

    /** The caller's transfers, newest first; MSG41 when not opened. */
    PageResponse<TransferResponse> transfers(UUID userId, Integer page, Integer pageSize);

    /** The caller's transaction history, newest first; MSG41 when not opened. */
    PageResponse<LedgerEntryResponse> ledger(UUID userId, LedgerQuery query);
}

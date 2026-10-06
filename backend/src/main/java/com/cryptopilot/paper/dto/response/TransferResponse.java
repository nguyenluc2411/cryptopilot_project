package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer between the caller's wallets.
 *
 * @param transferId the transfer
 * @param clientTransferId its idempotency key, as sent or as generated
 * @param asset the coin moved, e.g. {@code USDT}
 * @param from the wallet it left
 * @param to the wallet it entered
 * @param amount the amount moved
 * @param transferredAt when it happened
 */
public record TransferResponse(
        UUID transferId,
        String clientTransferId,
        String asset,
        WalletType from,
        WalletType to,
        BigDecimal amount,
        Instant transferredAt) {}

package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One change of a balance of the caller's paper account (the transaction history).
 *
 * @param entryId the entry
 * @param wallet the wallet whose balance changed
 * @param asset the coin, e.g. {@code USDT}
 * @param type what changed it
 * @param amount the signed change
 * @param balanceAfter the balance's total after it
 * @param refType the kind of row that caused it, or {@code null}
 * @param refId that row, or {@code null}
 * @param createdAt when it happened
 */
public record LedgerEntryResponse(
        UUID entryId,
        WalletType wallet,
        String asset,
        LedgerEntryType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        LedgerRefType refType,
        UUID refId,
        Instant createdAt) {}

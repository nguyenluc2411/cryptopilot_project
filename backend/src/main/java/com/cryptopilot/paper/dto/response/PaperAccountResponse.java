package com.cryptopilot.paper.dto.response;

import java.time.Instant;

/**
 * The caller's paper account. It is the Trader's own: the wallets, orders and fills belong to the Trader directly, so
 * the account has no key of its own.
 *
 * @param openedAt when the account was opened and its virtual funds granted
 */
public record PaperAccountResponse(Instant openedAt) {}

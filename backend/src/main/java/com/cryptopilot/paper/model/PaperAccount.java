package com.cryptopilot.paper.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Trader's paper trading account: the virtual funds granted once, and the wallets, orders and fills that followed.
 * It is not a row of its own. The rows belong to the Trader ({@code user_id}), and the account is open from the
 * instant its INITIAL_GRANT entries were written; nothing grants the funds again (Q-T5 as revised on 2026-10-06).
 *
 * <p>Rule: TR-04; Q-T5; D-117.
 *
 * @param userId the Trader who owns it
 * @param openedAt when its funds were granted
 */
public record PaperAccount(UUID userId, Instant openedAt) {

    public PaperAccount {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(openedAt, "openedAt");
    }
}

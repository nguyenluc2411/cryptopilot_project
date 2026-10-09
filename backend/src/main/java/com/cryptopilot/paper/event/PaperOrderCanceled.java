package com.cryptopilot.paper.event;

import java.util.UUID;

/**
 * A Trader cancelled a working order. Published inside the cancelling transaction; the paper matching engine reads it
 * after the commit and drops the order from its book.
 *
 * <p>Rule: TR-02; NSF-07.
 *
 * @param orderId the order
 */
public record PaperOrderCanceled(UUID orderId) {}

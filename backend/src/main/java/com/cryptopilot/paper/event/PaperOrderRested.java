package com.cryptopilot.paper.event;

import com.cryptopilot.paper.model.RestingOrder;
import java.util.Objects;

/**
 * A LIMIT order did not execute on arrival and now waits at its price. Published inside the placing transaction; the
 * paper matching engine reads it after the commit.
 *
 * <p>Rule: TR-02; NSF-07.
 *
 * @param order the order as the engine tracks it
 */
public record PaperOrderRested(RestingOrder order) {

    public PaperOrderRested {
        Objects.requireNonNull(order, "order");
    }
}

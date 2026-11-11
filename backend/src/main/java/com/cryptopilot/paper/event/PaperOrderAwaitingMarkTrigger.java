package com.cryptopilot.paper.event;

import com.cryptopilot.paper.model.TriggerOrder;
import java.util.Objects;

/**
 * A Futures conditional order was placed and waits for the mark price to trigger it (TR-20). Published inside the
 * placing transaction; the paper matching engine reads it after the commit and watches the pair's mark prices for it.
 *
 * <p>Rule: TR-20.
 *
 * @param order the order as the engine watches it
 */
public record PaperOrderAwaitingMarkTrigger(TriggerOrder order) {

    public PaperOrderAwaitingMarkTrigger {
        Objects.requireNonNull(order, "order");
    }
}

package com.cryptopilot.paper.event;

import com.cryptopilot.paper.model.TriggerOrder;
import java.util.Objects;

/**
 * A stop limit or a trailing stop was placed and waits for the last price to trigger it. Published inside the placing
 * transaction; the paper matching engine reads it after the commit.
 *
 * <p>Rule: TR-09, TR-11.
 *
 * @param order the order as the engine watches it
 */
public record PaperOrderAwaitingTrigger(TriggerOrder order) {

    public PaperOrderAwaitingTrigger {
        Objects.requireNonNull(order, "order");
    }
}

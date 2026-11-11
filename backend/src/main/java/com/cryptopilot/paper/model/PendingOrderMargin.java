package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;

/**
 * An open Futures order of one pair as the order margin sees it: its side, the quantity still to fill and the cost of
 * that quantity as if all of it opened a position.
 *
 * <p>Rule: TR-15; D-127.
 *
 * @param side the order side
 * @param quantity the quantity still to fill, positive
 * @param cost the cost of that quantity, initial margin plus open loss
 */
public record PendingOrderMargin(OrderSide side, BigDecimal quantity, BigDecimal cost) {}

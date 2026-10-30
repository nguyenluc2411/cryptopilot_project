package com.cryptopilot.paper.model;

import java.math.BigDecimal;

/**
 * What one fill does to a one-way Futures position: the part of it that closes the position, the profit or loss that
 * part realises, the part that opens or increases one, and the position afterwards. A fill larger than an opposite
 * position does both, closing it and opening the other way (a reversal, stored as two fill rows).
 *
 * <p>Rule: TR-16; D-127.
 *
 * @param closedQuantity the quantity that reduced or closed the position, zero when none did
 * @param realizedPnl the profit or loss of the closed quantity, {@code side × closed × (P − E)}, at the amount scale
 * @param openedQuantity the quantity that opened or increased a position, zero when none did
 * @param quantityAfter the signed size afterwards, positive for long and negative for short, zero when flat
 * @param entryPriceAfter the entry price afterwards, {@code null} when flat
 */
public record PositionFill(
        BigDecimal closedQuantity,
        BigDecimal realizedPnl,
        BigDecimal openedQuantity,
        BigDecimal quantityAfter,
        BigDecimal entryPriceAfter) {

    /** Whether the fill closed one position and opened the opposite one. */
    public boolean reverses() {
        return closedQuantity.signum() > 0 && openedQuantity.signum() > 0;
    }
}

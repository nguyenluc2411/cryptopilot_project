package com.cryptopilot.trading;

import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The position of an executed plan as the journal holds it, for the plan detail (UC-20, SCR-18).
 *
 * <p>Rule: BR-34, BR-36; D-119.
 *
 * @param planId the plan
 * @param status OPEN or CLOSED
 * @param entryPrice the actual fill price
 * @param quantity the quantity held
 * @param entryTime when the fill counts (D-78)
 * @param entryFee the fee of the entry fill
 */
public record RecordedPosition(
        UUID planId,
        PositionStatus status,
        BigDecimal entryPrice,
        BigDecimal quantity,
        Instant entryTime,
        BigDecimal entryFee) {

    public RecordedPosition {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(entryTime, "entryTime");
        Objects.requireNonNull(entryFee, "entryFee");
    }
}

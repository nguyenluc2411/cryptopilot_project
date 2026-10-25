package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a Trader already has open besides the plan being saved, read once for one calculation and the activation it
 * may allow: the slots of {@code ACTIVE_PLAN_MAX} in use and the other open risk.
 *
 * <p>Rule: BR-29, BR-62; D-63, D-119.
 *
 * @param usedPlanSlots ACTIVE plans plus open positions (BR-62); only counted when the call activates, otherwise 0
 * @param otherOpenRisk the risk amount of the other ACTIVE plans plus that of the open positions (BR-29)
 */
public record OpenExposure(long usedPlanSlots, BigDecimal otherOpenRisk) {

    public OpenExposure {
        Objects.requireNonNull(otherOpenRisk, "otherOpenRisk");
    }
}

package com.cryptopilot.trading.exception;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.UUID;

/**
 * A plan cannot be activated while it holds a BLOCKING warning (MSG18). The plan stays a DRAFT and can still be
 * edited and saved.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-32; MSG18.
 *
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10 "Aggregates" (the
 * root checks its invariants before it commits to a change).
 */
public class PlanActivationBlockedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** @param planId the plan that was not activated */
    public PlanActivationBlockedException(UUID planId) {
        super(
                ErrorCode.TRADING_BLOCKING_WARNING,
                "plan " + planId + " holds a BLOCKING warning and cannot be activated");
    }
}

package com.cryptopilot.trading.exception;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.util.UUID;

/**
 * An action the plan's current status does not allow: an edit outside DRAFT, a transition BR-32 does not list, or an
 * expiry before its time.
 *
 * <p>Rule: BR-32; D-66.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 "Aggregates" (the root refuses
 * any change that would break an invariant of the whole).
 */
public class IllegalPlanStateException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final PlanStatus status;

    /**
     * @param planId the plan
     * @param status the plan's current status
     * @param action what was refused, e.g. {@code "edit"} or {@code "move to EXECUTED"}
     */
    public IllegalPlanStateException(UUID planId, PlanStatus status, String action) {
        super(
                ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID,
                "plan " + planId + " is " + status + ", cannot " + action);
        this.status = status;
    }

    /** The status the plan was in when the action was refused. */
    public PlanStatus status() {
        return status;
    }
}

package com.cryptopilot.watchlist.exception;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import java.util.UUID;

/**
 * An action the alert's current status does not allow: pausing an alert that is not ACTIVE, resuming one that is not
 * PAUSED.
 *
 * <p>Rule: SRS 3.4.3.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 "Aggregates" (the root refuses
 * any change that would break an invariant of the whole).
 */
public class IllegalAlertStateException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final AlertStatus status;

    /**
     * @param alertId the alert
     * @param status the alert's current status
     * @param target the status that was refused
     */
    public IllegalAlertStateException(UUID alertId, AlertStatus status, AlertStatus target) {
        super(
                ErrorCode.ALERT_STATUS_TRANSITION_INVALID,
                "alert " + alertId + " is " + status + ", cannot become " + target);
        this.status = status;
    }

    /** The status the alert was in when the action was refused. */
    public AlertStatus status() {
        return status;
    }
}

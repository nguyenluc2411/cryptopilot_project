package com.cryptopilot.trading.dto.response;

import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;

/**
 * One warning of a plan, shown with its severity (MSG17).
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; MSG17.
 *
 * @param type the kind of warning
 * @param severity INFO, WARNING or BLOCKING
 * @param message the warning with the values that raised it
 */
public record PlanWarningResponse(WarningType type, WarningSeverity severity, String message) {}

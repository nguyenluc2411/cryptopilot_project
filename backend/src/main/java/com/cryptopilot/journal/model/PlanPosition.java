package com.cryptopilot.journal.model;

import com.cryptopilot.trading.model.enums.PositionStatus;
import java.util.UUID;

/**
 * The position status of one plan's journal record.
 *
 * <p>Rule: BR-34; SRS 3.5.2.
 *
 * @param planId the plan
 * @param status OPEN or CLOSED
 */
public record PlanPosition(UUID planId, PositionStatus status) {}

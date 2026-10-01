package com.cryptopilot.trading.dto.response;

import com.cryptopilot.trading.model.enums.PlanStatus;
import java.time.Instant;

/**
 * One step of a plan's status history (SCR-18): the status and when the plan reached it (D-67).
 *
 * <p>Rule: BR-32.
 *
 * @param status the status reached
 * @param at when
 */
public record StatusChangeResponse(PlanStatus status, Instant at) {}

package com.cryptopilot.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * New levels for an OPEN position (SCR-18). A level left out, or {@code null}, keeps its current value; at least one
 * must be given. Each is rounded to the pair's tick before it is checked against the current last price.
 *
 * <p>Rule: BR-22, BR-28, BR-30; UC-21; D-124.
 *
 * @param stopLoss the new stop loss, or {@code null} to keep the current one
 * @param takeProfit the new take profit, or {@code null} to keep the current one
 */
public record ChangePositionLevelsRequest(
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopLoss,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal takeProfit) {}

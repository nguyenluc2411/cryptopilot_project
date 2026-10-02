package com.cryptopilot.watchlist.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.AlertRuleInput;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The body of {@code PUT /alerts/{id}} (SCR-14, Edit): the whole rule again. The pair stays the one the alert was
 * created on. Editing a TRIGGERED or EXPIRED alert makes it ACTIVE again (SRS 3.4.3); the status itself is never part
 * of the body.
 *
 * <p>Rule: UC-14; BR-19; SRS 3.4.2, 3.4.3.
 */
public record UpdateAlertRequest(
        @NotNull(message = "MSG01") MarketType market,

        @NotNull(message = "MSG01") AlertType type,

        AlertIndicator indicator,

        String timeframe,

        @NotNull(message = "MSG01") ConditionOperator condition,

        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal threshold,

        @NotNull(message = "MSG01") TriggerMode triggerMode,

        @Min(value = 1, message = "MSG15") Integer cooldownMinutes,

        Boolean notifyEmail,

        Boolean notifyPush,

        Instant expiresAt) {

    /** The rule as entered; an absent channel is off. */
    public AlertRuleInput rule() {
        return new AlertRuleInput(
                market,
                type,
                indicator,
                timeframe,
                condition,
                threshold,
                triggerMode,
                cooldownMinutes,
                Boolean.TRUE.equals(notifyEmail),
                Boolean.TRUE.equals(notifyPush),
                expiresAt);
    }
}

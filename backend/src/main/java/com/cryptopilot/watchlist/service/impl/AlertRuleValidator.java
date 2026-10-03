package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.AlertDefinition;
import com.cryptopilot.watchlist.model.AlertRuleInput;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Checks the fields of an alert rule together and fills in what the server fixes. Bean Validation has already checked
 * each field alone (required, at least one minute, the column's digits); this class checks what depends on another
 * field: which fields a type and an indicator take, the timeframe, the threshold's range, the cooldown of EVERY_TIME
 * and the expiry window. Every broken rule is reported, field by field, in one response.
 *
 * <ul>
 *   <li>PRICE: a target above zero, no indicator and no timeframe; the target is put on the tick by
 *       {@link #onTick}.
 *   <li>RSI_14: a timeframe and a level from 0 to 100.
 *   <li>MACD_CROSS, EMA_CROSS: a timeframe and a cross condition; no threshold, since one line crosses the other
 *       (TECHNICAL_DESIGN 7.9), so the stored threshold is {@code null} (D-76).
 *   <li>FUNDING_RATE, OPEN_INTEREST_CHANGE: Futures only and a threshold; the timeframe is 1h, set here, and a client
 *       that sends one is refused (A-40).
 *   <li>EVERY_TIME needs a cooldown (BR-19); an expiry is after now and at most 90 days ahead (SRS 3.4.2).
 * </ul>
 *
 * <p>Rule: BR-19, BR-20; SRS 3.4.2; TECHNICAL_DESIGN 7.9; A-40; MSG01, MSG15.
 *
 * <p>Reference: Evans, E. &amp; Fowler, M. (1997). <i>Specifications</i> (each rule a predicate over the candidate,
 * combined and reported together). Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 9
 * "Specification".
 */
final class AlertRuleValidator {

    /** How far ahead an expiry may be (SRS 3.4.2). */
    static final Duration MAX_EXPIRY = Duration.ofDays(90);

    /** The timeframe the server sets for the Futures indicators (A-40). */
    static final String FUTURES_INDICATOR_TIMEFRAME = "1h";

    private static final Set<String> TIMEFRAMES = Set.of("15m", "1h", "4h", "1d");

    private static final BigDecimal RSI_MAX = BigDecimal.valueOf(100);

    private static final String REQUIRED = "MSG01";

    private static final String NOT_APPLICABLE = "MSG01";

    private static final String OUT_OF_RANGE = "MSG15";

    private AlertRuleValidator() {}

    /**
     * The checked rule, before a PRICE target is put on the tick.
     *
     * @throws FieldValidationException naming every field that breaks a rule
     */
    static AlertDefinition validate(AlertRuleInput input, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        AlertIndicator indicator = input.indicator();
        String timeframe = input.timeframe();
        BigDecimal threshold = input.threshold();

        if (input.type() == AlertType.PRICE) {
            reject(errors, "indicator", indicator);
            reject(errors, "timeframe", timeframe);
            if (threshold == null) {
                errors.put("threshold", REQUIRED);
            } else if (threshold.signum() <= 0) {
                errors.put("threshold", OUT_OF_RANGE);
            }
        } else if (indicator == null) {
            errors.put("indicator", REQUIRED);
        } else if (indicator.futuresOnly()) {
            if (input.market() != MarketType.FUTURES) {
                errors.put("market", NOT_APPLICABLE);
            }
            reject(errors, "timeframe", timeframe);
            timeframe = FUTURES_INDICATOR_TIMEFRAME;
            require(errors, "threshold", threshold);
        } else {
            if (timeframe == null) {
                errors.put("timeframe", REQUIRED);
            } else if (!TIMEFRAMES.contains(timeframe)) {
                errors.put("timeframe", NOT_APPLICABLE);
            }
            if (indicator.signCross()) {
                if (!input.condition().isCross()) {
                    errors.put("condition", NOT_APPLICABLE);
                }
                reject(errors, "threshold", threshold);
            } else if (threshold == null) {
                errors.put("threshold", REQUIRED);
            } else if (threshold.signum() < 0 || threshold.compareTo(RSI_MAX) > 0) {
                errors.put("threshold", OUT_OF_RANGE);
            }
        }

        if (input.triggerMode() == TriggerMode.EVERY_TIME && input.cooldownMinutes() == null) {
            errors.put("cooldownMinutes", REQUIRED);
        }
        if (input.cooldownMinutes() != null && input.cooldownMinutes() < 1) {
            errors.put("cooldownMinutes", OUT_OF_RANGE);
        }
        Instant expiresAt = input.expiresAt();
        if (expiresAt != null && (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_EXPIRY)))) {
            errors.put("expiresAt", OUT_OF_RANGE);
        }

        if (!errors.isEmpty()) {
            throw new FieldValidationException("the alert rule breaks " + errors.keySet(), errors);
        }
        return new AlertDefinition(
                input.market(),
                input.type(),
                input.type() == AlertType.PRICE ? null : indicator,
                input.type() == AlertType.PRICE ? null : timeframe,
                input.condition(),
                threshold,
                input.triggerMode(),
                input.cooldownMinutes(),
                input.notifyEmail(),
                input.notifyPush(),
                expiresAt);
    }

    /**
     * A PRICE target rounded to the pair's tick, halves up, as plan prices are (BR-30, D-71); other rules unchanged.
     *
     * @throws FieldValidationException when the target rounds to zero
     */
    static AlertDefinition onTick(AlertDefinition definition, PairFilters filters) {
        if (definition.type() != AlertType.PRICE) {
            return definition;
        }
        BigDecimal target = filters.roundPrice(definition.threshold());
        if (target.signum() <= 0) {
            throw new FieldValidationException(
                    "target " + definition.threshold() + " rounds to " + target, Map.of("threshold", OUT_OF_RANGE));
        }
        return definition.withThreshold(target);
    }

    /** The resume of an alert whose expiry has passed is refused: the Trader edits it to set a new one. */
    static void requireNotExpired(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new FieldValidationException(
                    "the expiry " + expiresAt + " has passed", Map.of("expiresAt", OUT_OF_RANGE));
        }
    }

    private static void require(Map<String, String> errors, String field, Object value) {
        if (value == null) {
            errors.put(field, REQUIRED);
        }
    }

    private static void reject(Map<String, String> errors, String field, Object value) {
        if (value != null) {
            errors.put(field, NOT_APPLICABLE);
        }
    }
}

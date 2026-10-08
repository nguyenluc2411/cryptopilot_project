package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.watchlist.model.PriceAlert;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Whether a PRICE alert whose condition is met may fire now, by its trigger mode. An early filter in memory only: the
 * conditional update that records the trigger repeats the same checks and has the last word.
 *
 * <ul>
 *   <li>ONCE: fires; an alert in the book has not fired since it became ACTIVE, because firing makes it TRIGGERED
 *       and takes it out of the book.
 *   <li>ONCE_PER_BAR: at most once per 1h candle (BR-19 fixes 1h for PRICE alerts).
 *   <li>EVERY_TIME: when at least the cooldown has passed since the last trigger; exactly the cooldown is enough.
 * </ul>
 *
 * <p>Rule: BR-19; TECHNICAL_DESIGN 7.9.
 */
public final class TriggerPolicy {

    /** The candle of a PRICE alert's ONCE_PER_BAR (BR-19). */
    public static final ChronoUnit PRICE_BAR = ChronoUnit.HOURS;

    private TriggerPolicy() {}

    /** The open time of the 1h candle {@code at} falls in. */
    public static Instant barOpenTime(Instant at) {
        return at.truncatedTo(PRICE_BAR);
    }

    /**
     * Whether the alert may fire at {@code now} for a price in the candle opened at {@code barOpenTime}.
     */
    public static boolean allows(PriceAlert alert, Instant now, Instant barOpenTime) {
        return switch (alert.triggerMode()) {
            case ONCE -> true;
            case ONCE_PER_BAR -> !barOpenTime.equals(alert.lastBarOpenTime());
            case EVERY_TIME ->
                alert.lastTriggeredAt() == null
                        || !now.isBefore(alert.lastTriggeredAt().plus(cooldown(alert)));
        };
    }

    private static Duration cooldown(PriceAlert alert) {
        return Duration.ofMinutes(alert.cooldownMinutes() == null ? 0 : alert.cooldownMinutes());
    }
}

package com.cryptopilot.market.calculator;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a futures pair has a funding settlement the system has not stored yet, from the two facts the
 * exchange states — the next funding time and, when it publishes one, the funding interval — and the latest
 * settlement already stored.
 *
 * <p>The settlement that must already exist is the one just before the next: {@code nextFundingTime − interval}.
 * The pair is due when that instant has passed and nothing at or after it is stored. The exchange stamps a
 * settlement a few milliseconds after the round instant (e.g. {@code 08:00:00.005}), so "at or after" is what
 * makes such a row count as the expected one.
 *
 * <p>No interval is ever assumed (BR-11). When the exchange states none for the symbol — its funding
 * information lists only symbols whose settings were adjusted — the pair is due on every run, which costs one
 * request and can never miss a settlement, whatever the real interval is.
 *
 * <p>A pair with nothing stored is always due: its history has to be read once.
 *
 * <p>Rule: BR-11; NSF-04.
 *
 * <p>Reference: Binance. <i>USDⓈ-M Futures API</i>, "Mark Price" ({@code nextFundingTime}) and "Get Funding Rate
 * Info" ({@code fundingIntervalHours}, listed only for adjusted symbols).
 */
public final class FundingSettlementDue {

    private FundingSettlementDue() {}

    /**
     * Whether the settlements after {@code latestStored} should be read from the exchange now.
     *
     * @param latestStored the latest settlement instant stored for the pair, or {@code null} when none is
     * @param nextFundingTime the next settlement, as the exchange reports it
     * @param interval the funding interval the exchange states for the symbol, or empty when it states none
     * @param now the present instant
     */
    public static boolean isDue(
            Instant latestStored, Instant nextFundingTime, Optional<Duration> interval, Instant now) {
        Objects.requireNonNull(nextFundingTime, "nextFundingTime must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (latestStored == null || interval.isEmpty()) {
            return true;
        }
        if (interval.get().isNegative() || interval.get().isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        Instant previous = nextFundingTime.minus(interval.get());
        return !previous.isAfter(now) && latestStored.isBefore(previous);
    }
}

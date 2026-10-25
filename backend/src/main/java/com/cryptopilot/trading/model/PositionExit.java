package com.cryptopilot.trading.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An open position the matching engine has decided to close: the exit and the excursions up to it. It is stored by a
 * compare-and-set OPEN → CLOSED on the journal row, so of two racing writers exactly one closes it.
 *
 * <p>Rule: BR-35, NSF-07; TECHNICAL_DESIGN 7.7; D-78; D-112 R5.
 *
 * @param position the position as the engine tracked it
 * @param exit the reason, the price before slippage and the time
 * @param excursion the most adverse and the most favourable price up to the exit, clamped at the exit price
 */
public record PositionExit(TrackedPosition position, Exit exit, Excursion excursion) implements PlanOutcome {

    public PositionExit {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(excursion, "excursion");
    }

    @Override
    public UUID planId() {
        return position.planId();
    }

    @Override
    public Instant at() {
        return exit.exitedAt();
    }
}

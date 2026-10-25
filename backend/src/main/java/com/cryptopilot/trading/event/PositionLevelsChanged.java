package com.cryptopilot.trading.event;

import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.model.TrackedPosition;
import java.util.Objects;

/**
 * The Trader changed the stop loss or take profit of an OPEN position, and the journal row holds the new levels at a
 * new version. Published inside the change's transaction; after the commit the matching engine replaces the levels in
 * its exit books and keeps the earlier ones for the candles that opened before the change.
 *
 * <p>Rule: BR-35, NSF-07; UC-21; D-119 (5), D-124.
 *
 * @param position the position as the engine tracks it from now on: the new levels and the row's new version
 * @param change the change, with the levels it replaced and its instant
 */
public record PositionLevelsChanged(TrackedPosition position, PositionLevelChange change) {

    public PositionLevelsChanged {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(change, "change");
    }
}

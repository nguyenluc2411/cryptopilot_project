package com.cryptopilot.trading;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The funding of a closed simulated Futures position, recomputed from the stored settlements every time it is asked:
 * what the journal asks again for a trade whose funding was incomplete at its close, once a late settlement may have
 * been stored. The {@code trading} module computes it; the journal only stores the answer.
 *
 * <p>Rule: BR-37; A-05; D-97 note of 2026-10-24; TECHNICAL_DESIGN 7.8.
 */
public interface TradeFunding {

    /** The funding of a position open from {@code entryTime} to {@code exitTime}, complete or not. */
    ClosedTradeFunding fundingOf(
            UUID pairId, Direction direction, BigDecimal quantity, Instant entryTime, Instant exitTime);
}

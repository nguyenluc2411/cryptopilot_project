package com.cryptopilot.trading.service;

import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The funding of a simulated Futures position, computed from the stored settlements of its pair every time it is
 * asked for, never stored per settlement: on close for the closed trade, on read for an open one. An incomplete
 * answer becomes complete by asking again once the late settlement is stored. Spot has no funding and is never
 * asked.
 *
 * <p>Rule: BR-37; NSF-08 as realized by A-05; D-97; TECHNICAL_DESIGN 7.8.
 */
public interface FundingService {

    /** The funding of a position that was open from {@code entryTime} to {@code exitTime}. */
    FundingFee fundingFee(UUID pairId, Direction direction, BigDecimal quantity, Instant entryTime, Instant exitTime);

    /** The funding of a position still open, from {@code entryTime} to the present. */
    FundingFee fundingSoFar(UUID pairId, Direction direction, BigDecimal quantity, Instant entryTime);
}

package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plan's entry filled and its position is OPEN in the journal, on any fill path: a MARKET or marketable LIMIT filled at
 * activation, or a resting LIMIT or a triggered STOP the matching engine filled. Published inside the transaction whose
 * compare-and-set moved the plan to EXECUTED, so a fill that rolls back tells nobody, and a fill the compare-and-set
 * refused (a replayed candle, a racing cancel) publishes nothing. It only informs: the matching engine does not read it
 * ({@link PositionOpened} adds an activation fill to the exit books). The Trader is told with MSG22.
 *
 * <p>Rule: BR-33, BR-34; NSF-07; MSG22; D-119; Q-81.
 *
 * @param planId the plan
 * @param positionId the journal record the fill opened
 * @param userId the owner
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT, named in MSG22
 * @param symbol the pair's symbol, or {@code null} when it could not be read
 * @param fillPrice the stored fill price, slippage included
 * @param quantity the position's quantity
 * @param liquidity MAKER or TAKER
 * @param filledAt the fill time: the open of the candle that reached the entry, or the activation instant (D-78)
 */
public record PlanFilled(
        UUID planId,
        UUID positionId,
        UUID userId,
        MarketType market,
        Direction direction,
        String symbol,
        BigDecimal fillPrice,
        BigDecimal quantity,
        LiquidityRole liquidity,
        Instant filledAt) {}

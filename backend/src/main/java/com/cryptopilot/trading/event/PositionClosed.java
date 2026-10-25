package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.CloseReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A simulated position has closed: its take profit or stop loss was reached, it was liquidated, or the Trader closed
 * it (UC-21). Published inside
 * the transaction whose compare-and-set moved the journal row to CLOSED, and only then, so a listener after the commit
 * sees each close once; a racing exit or a replayed one publishes nothing. The amounts are those of the journal row,
 * and {@code netPnl} is the one net formula of BR-38, the {@code {pnl}} of MSG23 and MSG24 (Q-56).
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; NSF-07; D-97; Q-56, Q-57.
 *
 * @param positionId the journal record
 * @param planId the plan the position came from
 * @param userId the owner
 * @param market SPOT or FUTURES
 * @param pairId the pair, whose books the engine takes the position out of
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}, or {@code null} when it could not be read
 * @param closeReason TAKE_PROFIT, STOP_LOSS, LIQUIDATION or MANUAL
 * @param exitPrice the stored exit price
 * @param realizedPnl the price result only
 * @param feeAmount the entry fee plus the exit fee
 * @param netPnl realized minus fees plus the funding known so far
 * @param provisional whether the funding behind {@code netPnl} is incomplete
 * @param closedAt when the exit counts: the open time of the candle that decided it (D-78), or the instant of a
 *     manual close
 */
public record PositionClosed(
        UUID positionId,
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        String symbol,
        CloseReason closeReason,
        BigDecimal exitPrice,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal netPnl,
        boolean provisional,
        Instant closedAt) {}

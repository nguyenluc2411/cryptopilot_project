package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A margin call, pushed on {@code /user/queue/futures.marginCall} (TR-43, plan 4.10): the margin ratio of an isolated
 * position, or of the cross positions together, reached {@code cryptopilot.paper.futures.margin-call-ratio}. Binance:
 * the {@code MARGIN_CALL} event of the user data stream; it publishes no threshold.
 *
 * @param symbol the isolated position's pair, or {@code null} for the cross positions
 * @param positionSide the isolated position's side, or {@code null}
 * @param marginType ISOLATED or CROSSED
 * @param markPrice the mark that reached the ratio, or {@code null} for the cross positions
 * @param marginRatio maintenance margin over margin balance; liquidation is due at 1
 * @param maintenanceMargin the maintenance margin of the position, or of the cross positions
 * @param marginBalance the isolated wallet plus unrealized profit, or the cross wallet plus the cross unrealized profit
 * @param eventTime when the mark came
 */
public record FuturesMarginCallResponse(
        String symbol,
        PositionSide positionSide,
        MarginType marginType,
        BigDecimal markPrice,
        BigDecimal marginRatio,
        BigDecimal maintenanceMargin,
        BigDecimal marginBalance,
        Instant eventTime) {}

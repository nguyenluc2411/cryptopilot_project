package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the caller's paper account is worth now and was worth at the start of each day (TR-13).
 *
 * @param spotEquity the Spot wallet now, in USDT, at the current last prices
 * @param futuresEquity the Futures wallet now, in USDT
 * @param totalEquity the two together
 * @param todayPnl the total now less the total at the day's snapshot; {@code null}, not known, while the day has none
 * @param todayPnlPercent that as a percent of the snapshot, 2 decimals; {@code null} without it
 * @param history the start of each day of the period asked for, oldest first
 */
public record EquityResponse(
        BigDecimal spotEquity,
        BigDecimal futuresEquity,
        BigDecimal totalEquity,
        BigDecimal todayPnl,
        BigDecimal todayPnlPercent,
        List<Point> history) {

    /**
     * The account at the start of one day.
     *
     * @param date the day, in UTC
     * @param spotEquity the Spot wallet
     * @param futuresEquity the Futures wallet
     * @param totalEquity the two together
     */
    public record Point(LocalDate date, BigDecimal spotEquity, BigDecimal futuresEquity, BigDecimal totalEquity) {}
}

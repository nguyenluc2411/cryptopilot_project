package com.cryptopilot.journal.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of the equity curve.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-38.
 *
 * @param day the exit day
 * @param trades the trades closed that day
 * @param netPnl their net P/L
 * @param cumulativeNetPnl the net P/L from the start of the period to the end of the day
 */
public record EquityPoint(LocalDate day, long trades, BigDecimal netPnl, BigDecimal cumulativeNetPnl) {}

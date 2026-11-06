package com.cryptopilot.journal.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One point of the cumulative profit/loss curve of SCR-23. Only days with an exit have a point.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-38.
 *
 * @param day the exit day, UTC+7
 * @param trades the trades closed that day
 * @param netPnl their net P/L
 * @param cumulativeNetPnl the net P/L from the start of the period to the end of the day
 */
public record EquityPointResponse(LocalDate day, long trades, BigDecimal netPnl, BigDecimal cumulativeNetPnl) {}

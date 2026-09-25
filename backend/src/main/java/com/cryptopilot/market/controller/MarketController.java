package com.cryptopilot.market.controller;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.dto.response.CandlesResponse;
import com.cryptopilot.market.dto.response.FuturesMetricsResponse;
import com.cryptopilot.market.dto.response.FuturesStatsResponse;
import com.cryptopilot.market.dto.response.PairResponse;
import com.cryptopilot.market.dto.response.SpotStatsResponse;
import com.cryptopilot.market.service.CandleService;
import com.cryptopilot.market.service.MarketStatsService;
import com.cryptopilot.market.service.PairService;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public market data of UC-09: the enabled pairs, their closed candles, their latest statistics and the futures
 * metrics. Read-only and open to a Guest, as the filter chain grants {@code GET /api/v1/market/**} (SRS 3.1.3, "Query
 * Public Data"); the computed analysis of SCR-10 and SCR-11 is not here but under {@code /analysis/**}.
 *
 * <p>Times are ISO 8601 in UTC, decimals are strings (TECHNICAL_DESIGN 8). A value not stored yet is absent from the
 * answer, never zero.
 *
 * <p>Rule: UC-09, BR-07, BR-08, BR-10, BR-11; SRS 3.1.3, 3.3.1; TECHNICAL_DESIGN 8; D-45, D-46.
 */
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketController {

    private final PairService pairs;
    private final CandleService candles;
    private final MarketStatsService stats;

    /** The pairs enabled on a market (BR-07), a page at a time, in display order. */
    @GetMapping("/pairs")
    public PageResponse<PairResponse> pairs(
            @RequestParam String market,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return pairs.enabledPairs(market, page, pageSize);
    }

    /** The latest closed candles of a series opened in {@code [from, to)}, oldest first (BR-08). */
    @GetMapping("/{market}/{symbol}/candles")
    public CandlesResponse candles(
            @PathVariable String market,
            @PathVariable String symbol,
            @RequestParam String tf,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return candles.closedCandles(market, symbol, tf, limit, from, to);
    }

    /** The latest stored ticker of a Spot pair. */
    @GetMapping("/spot/{symbol}/stats")
    public SpotStatsResponse spotStats(@PathVariable String symbol) {
        return stats.spotStats(symbol);
    }

    /** The latest stored mark price, open interest, long/short ratio and settlement of a futures pair (D-45). */
    @GetMapping("/futures/{symbol}/stats")
    public FuturesStatsResponse futuresStats(@PathVariable String symbol) {
        return stats.futuresStats(symbol);
    }

    /** The open interest, long/short ratio and settled funding series of a futures pair over {@code [from, to)}. */
    @GetMapping("/futures/{symbol}/metrics")
    public FuturesMetricsResponse futuresMetrics(
            @PathVariable String symbol,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return stats.futuresMetrics(symbol, from, to);
    }
}

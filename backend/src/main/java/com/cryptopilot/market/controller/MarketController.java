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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Market data",
        description = "Public market data of UC-09: pairs, closed candles, statistics, futures metrics. Guest.")
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketController {

    private final PairService pairs;
    private final CandleService candles;
    private final MarketStatsService stats;

    /** The pairs enabled on a market (BR-07), a page at a time, in display order. */
    @Operation(summary = "Pairs enabled on a market, a page at a time (BR-07)")
    @ApiResponse(responseCode = "200", description = "One page of pairs")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @GetMapping("/pairs")
    public PageResponse<PairResponse> pairs(
            @Parameter(description = "spot or futures, in any case") @RequestParam String market,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return pairs.enabledPairs(market, page, pageSize);
    }

    /** The latest closed candles of a series opened in {@code [from, to)}, oldest first (BR-08). */
    @Operation(summary = "Closed candles of a series, oldest first (BR-08)")
    @ApiResponse(responseCode = "200", description = "The candles")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no pair with that symbol is enabled on that market (BR-07)")
    @GetMapping("/{market}/{symbol}/candles")
    public CandlesResponse candles(
            @Parameter(description = "spot or futures, in any case") @PathVariable String market,
            @PathVariable String symbol,
            @Parameter(description = "15m, 1h, 4h or 1d (BR-08)") @RequestParam String tf,
            @Parameter(description = "1 to 1000; default 500") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Earliest instant, ISO 8601 UTC, inclusive") @RequestParam(required = false)
                    Instant from,
            @Parameter(
                            description =
                                    "Instant the values must be before, ISO 8601 UTC; default now, never in the future")
                    @RequestParam(required = false)
                    Instant to) {
        return candles.closedCandles(market, symbol, tf, limit, from, to);
    }

    /** The latest stored ticker of a Spot pair. */
    @Operation(summary = "Latest stored ticker of a Spot pair")
    @ApiResponse(responseCode = "200", description = "The statistics; the ticker is absent until one is stored")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no pair with that symbol is enabled on that market (BR-07)")
    @GetMapping("/spot/{symbol}/stats")
    public SpotStatsResponse spotStats(@PathVariable String symbol) {
        return stats.spotStats(symbol);
    }

    /** The latest stored mark price, open interest, long/short ratio and settlement of a futures pair (D-45). */
    @Operation(summary = "Latest stored price, open interest, long/short ratio and settlement (D-45)")
    @ApiResponse(
            responseCode = "200",
            description = "The statistics; each block has its own instant and is absent until stored")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no pair with that symbol is enabled on that market (BR-07)")
    @GetMapping("/futures/{symbol}/stats")
    public FuturesStatsResponse futuresStats(@PathVariable String symbol) {
        return stats.futuresStats(symbol);
    }

    /** The open interest, long/short ratio and settled funding series of a futures pair over {@code [from, to)}. */
    @Operation(summary = "Sparse futures metric series over a range (D-45, BR-10)")
    @ApiResponse(responseCode = "200", description = "The series")
    @ApiResponse(responseCode = "400", description = "MSG01: a parameter or the body is invalid")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no pair with that symbol is enabled on that market (BR-07)")
    @GetMapping("/futures/{symbol}/metrics")
    public FuturesMetricsResponse futuresMetrics(
            @PathVariable String symbol,
            @Parameter(description = "Earliest instant, ISO 8601 UTC, inclusive") @RequestParam(required = false)
                    Instant from,
            @Parameter(
                            description =
                                    "Instant the values must be before, ISO 8601 UTC; default now, never in the future")
                    @RequestParam(required = false)
                    Instant to) {
        return stats.futuresMetrics(symbol, from, to);
    }
}

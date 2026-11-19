package com.cryptopilot.market.controller;

import com.cryptopilot.market.dto.response.MarketStatusResponse;
import com.cryptopilot.market.service.MarketStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The markets' data health, public as the market data is (D-158): what the Traders' banner shows when trading is
 * paused, recovering or back. The same payload is pushed on {@code /topic/market.status}.
 *
 * <p>Rule: NSF-03; D-50; D-158.
 */
@Tag(name = "Market data")
@RestController
@RequestMapping("/api/v1/market/status")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketStatusController {

    private final MarketStatusService service;

    @Operation(summary = "Whether each market may be traded now, and since when it may not")
    @ApiResponse(responseCode = "200", description = "SPOT, then FUTURES")
    @GetMapping
    public MarketStatusResponse status() {
        return service.status();
    }
}

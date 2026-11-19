package com.cryptopilot.market.controller;

import com.cryptopilot.market.dto.response.FallbackCandleResponse;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.DisplayPriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The display-only fallback prices and candles of a market whose exchange data is lost (D-158), public as the market
 * data is; the screens label them with their source. Clients that missed a push of
 * {@code /topic/market.fallback.<MARKET>} read them here.
 *
 * <p>Rule: BR-09; D-158.
 */
@Tag(name = "Market fallback prices", description = "Display-only prices while a market's exchange data is lost.")
@RestController
@RequestMapping("/api/v1/market/fallback")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FallbackPriceController {

    private final DisplayPriceService service;

    @Operation(summary = "The fallback prices of a market, while its exchange data is lost")
    @ApiResponse(responseCode = "200", description = "The prices with their source; none while the market is live")
    @GetMapping
    public FallbackPricesResponse prices(@Parameter(description = "SPOT or FUTURES") @RequestParam MarketType market) {
        return service.prices(market);
    }

    @Operation(summary = "The latest fallback one-minute candles of a pair, while its market's exchange data is lost")
    @ApiResponse(responseCode = "200", description = "Oldest first; none while the market is live")
    @ApiResponse(responseCode = "400", description = "MSG01: a limit outside 1 to 300")
    @GetMapping("/candles")
    public List<FallbackCandleResponse> candles(
            @Parameter(description = "SPOT or FUTURES") @RequestParam MarketType market,
            @Parameter(description = "The exchange symbol, e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(description = "1 to 300") @RequestParam(defaultValue = "120") int limit) {
        return service.candles(market, symbol, limit);
    }
}

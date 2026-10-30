package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.request.FuturesLiquidationCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesMaxOpenCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesOpenPriceCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesPnlCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesTargetCalcRequest;
import com.cryptopilot.paper.dto.response.FuturesCalculatorResponse;
import com.cryptopilot.paper.service.FuturesCalculatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Futures calculator of the exchange's app, one endpoint per tab. The exchange has no API for it; the figures are
 * the ones paper orders and positions get. Nothing is stored, so each is a POST only because it takes a body.
 *
 * <p>Rule: TR-25; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures calculator",
        description = "PnL, target price, liquidation price, max open and open price of paper Futures (TR-25)."
                + " Trader with the Futures feature; nothing is stored.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures/calculator")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesCalculatorController {

    private final FuturesCalculatorService calculator;

    @Operation(summary = "Initial margin, PnL and ROE of a position between two prices")
    @ApiResponse(responseCode = "200", description = "initialMargin, pnl, roe (percent), maxPositionAtLeverage")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or not allowed; PAPER_LEVERAGE (-4028): above the pair's")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the symbol is not traded on Futures")
    @PostMapping("/pnl")
    public FuturesCalculatorResponse pnl(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesPnlCalcRequest request) {
        return calculator.pnl(callerOf(caller), request);
    }

    @Operation(summary = "The exit price that makes a return on the initial margin")
    @ApiResponse(responseCode = "200", description = "targetPrice, on the tick; zero when no price makes the return")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or not allowed; PAPER_LEVERAGE (-4028): above the pair's")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the symbol is not traded on Futures")
    @PostMapping("/target")
    public FuturesCalculatorResponse target(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesTargetCalcRequest request) {
        return calculator.target(callerOf(caller), request);
    }

    @Operation(summary = "The liquidation price of one position alone in its margin")
    @ApiResponse(
            responseCode = "200",
            description = "liquidationPrice (zero when none), the walletBalance used, initialMargin,"
                    + " maxPositionAtLeverage")
    @ApiResponse(
            responseCode = "400",
            description =
                    "MSG01: walletBalance missing for CROSSED; MSG15: a field not allowed; PAPER_LEVERAGE" + " (-4028)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the symbol is not traded on Futures")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_MAX_POSITION_AT_LEVERAGE (-2027): the position is past what the leverage allows")
    @PostMapping("/liquidation")
    public FuturesCalculatorResponse liquidation(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesLiquidationCalcRequest request) {
        return calculator.liquidation(callerOf(caller), request);
    }

    @Operation(summary = "The largest position a balance opens with a LIMIT order at a price")
    @ApiResponse(responseCode = "200", description = "maxQuantity on the step, maxNotional, maxPositionAtLeverage")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or not allowed; PAPER_LEVERAGE (-4028): above the pair's")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the symbol is not traded on Futures")
    @PostMapping("/maxOpen")
    public FuturesCalculatorResponse maxOpen(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesMaxOpenCalcRequest request) {
        return calculator.maxOpen(callerOf(caller), request);
    }

    @Operation(summary = "The entry price of a position opened in several parts")
    @ApiResponse(responseCode = "200", description = "openPrice and the total quantity")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: no part, more than 20, or a field not allowed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the symbol is not traded on Futures")
    @PostMapping("/openPrice")
    public FuturesCalculatorResponse openPrice(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesOpenPriceCalcRequest request) {
        return calculator.openPrice(callerOf(caller), request);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

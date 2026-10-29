package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.request.FuturesClosePositionRequest;
import com.cryptopilot.paper.dto.request.FuturesPositionMarginRequest;
import com.cryptopilot.paper.dto.response.FuturesMarginHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionMarginResponse;
import com.cryptopilot.paper.service.FuturesPositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The position actions of paper Futures USDⓈ-M: the exchange's margin change of an isolated position
 * ({@code /fapi/v1/positionMargin}) and its history, and the trading screen's Close and Close All, which the exchange
 * offers on its app only. The differences from the exchange's API are those of {@link FuturesConfigController}.
 *
 * <p>Rule: TR-17; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures positions",
        description = "Margin of an isolated position, and closing positions (TR-17). Trader with the Futures feature.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesPositionController {

    private final FuturesPositionService futures;

    @Operation(summary = "Add or reduce an isolated position's margin. Binance: POST /fapi/v1/positionMargin")
    @ApiResponse(responseCode = "200", description = "The change, with the isolated wallet afterwards")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing, the amount is not positive (-4055) or the type not 1 or 2;"
                    + " PAPER_POSITION_SIDE (-4061); PAPER_INSUFFICIENT_MARGIN (-2019): more than the cross wallet's"
                    + " free cash")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_POSITION_MARGIN (-4049 cross position, -4051 more than can be removed, -4054 no"
                    + " position); PAPER_USER_IN_LIQUIDATION (-2023)")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE: no mark price to value the position")
    @PostMapping("/positionMargin")
    public FuturesPositionMarginResponse positionMargin(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesPositionMarginRequest request) {
        return futures.changeMargin(callerOf(caller), request);
    }

    @Operation(
            summary = "The margin changes made on a pair, newest first. Binance: GET /fapi/v1/positionMargin/history")
    @ApiResponse(responseCode = "200", description = "The changes")
    @ApiResponse(
            responseCode = "400",
            description = "MSG15: type not 1 or 2, limit outside 1 … 1000, or startTime" + " after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/positionMargin/history")
    public List<FuturesMarginHistoryResponse> positionMarginHistory(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(description = "1 additions, 2 reductions; both when absent") @RequestParam(required = false)
                    Integer type,
            @Parameter(description = "Made at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Made before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 500 when absent") @RequestParam(required = false) Integer limit) {
        return futures.marginHistory(callerOf(caller), symbol, type, startTime, endTime, limit);
    }

    @Operation(summary = "Close a position, in part or whole, or every position (the app's Close and Close All)")
    @ApiResponse(responseCode = "200", description = "The reduce-only orders placed, one per position closed")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or does not fit the type, or the share is less than one"
                    + " step; the refusals of POST /futures/order")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_REDUCE_ONLY_REJECTED (-2022): no position on the pair; the refusals of POST"
                    + " /futures/order")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE (MSG43): no current price to close at")
    @PostMapping("/closePosition")
    public List<FuturesOrderResponse> closePosition(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesClosePositionRequest request) {
        return futures.close(callerOf(caller), request);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

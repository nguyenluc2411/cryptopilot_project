package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.request.FuturesAlgoOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesAlgoOrderResponse;
import com.cryptopilot.paper.service.FuturesAlgoService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The conditional (algo) order endpoints of paper Futures USDⓈ-M, named after the exchange's
 * ({@code /fapi/v1/algoOrder} becomes {@code /api/v1/paper/futures/algoOrder}), with the same parameter and field
 * names; the differences are those of {@link FuturesConfigController}. A conditional order waits for the last or the
 * mark price; once triggered it places a LIMIT or MARKET order, listed by {@link FuturesOrderController}.
 *
 * <p>Rule: TR-20; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures conditional orders",
        description = "STOP, STOP_MARKET, TAKE_PROFIT and TAKE_PROFIT_MARKET of paper Futures, triggered by the last or"
                + " the mark price (TR-20). Trader with the Futures feature.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesAlgoController {

    private final FuturesAlgoService futures;

    @Operation(summary = "Place a conditional order. Binance: POST /fapi/v1/algoOrder")
    @ApiResponse(responseCode = "200", description = "The order, NEW: waiting for its trigger")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or does not fit the type (closePosition with a quantity is"
                    + " the exchange's -4137, with reduceOnly -1106; TRAILING_STOP_MARKET is not offered yet);"
                    + " PAPER_PRICE_FILTER, PAPER_LOT_SIZE, PAPER_MARKET_LOT_SIZE; PAPER_POSITION_SIDE (-4061)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_ORDER_WOULD_TRIGGER (-2021); PAPER_MAX_NUM_ALGO_ORDERS (-4045); PAPER_MAX_NUM_ORDERS"
                    + " (-2025); PAPER_USER_IN_LIQUIDATION (-2023); PAIR_NOT_TRADING; DATA_CONFLICT: the key was used"
                    + " for another order")
    @ApiResponse(
            responseCode = "503",
            description = "MARKET_PRICE_UNAVAILABLE (MSG43): no current price to judge the trigger against")
    @PostMapping("/algoOrder")
    public FuturesAlgoOrderResponse place(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesAlgoOrderRequest request) {
        return futures.place(callerOf(caller), request);
    }

    @Operation(summary = "Cancel a waiting conditional order. Binance: DELETE /fapi/v1/algoOrder")
    @ApiResponse(responseCode = "200", description = "The order, CANCELED")
    @ApiResponse(responseCode = "400", description = "MSG01: neither algoId nor clientAlgoId")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conditional order of the pair")
    @ApiResponse(responseCode = "409", description = "PAPER_ORDER_NOT_OPEN (-2011): it triggered or ended")
    @DeleteMapping("/algoOrder")
    public FuturesAlgoOrderResponse cancel(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @RequestParam(required = false) UUID algoId,
            @RequestParam(required = false) String clientAlgoId) {
        return futures.cancel(callerOf(caller), symbol, algoId, clientAlgoId);
    }

    @Operation(summary = "Cancel every waiting conditional order of a pair. Binance: DELETE /fapi/v1/algoOpenOrders")
    @ApiResponse(responseCode = "200", description = "The orders cancelled; empty when there were none")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @DeleteMapping("/algoOpenOrders")
    public List<FuturesAlgoOrderResponse> cancelAll(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol) {
        return futures.cancelAll(callerOf(caller), symbol);
    }

    @Operation(summary = "One conditional order. Binance: GET /fapi/v1/algoOrder")
    @ApiResponse(responseCode = "200", description = "The order, with the order it placed once triggered")
    @ApiResponse(responseCode = "400", description = "MSG01: neither algoId nor clientAlgoId")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: no such conditional order of the pair")
    @GetMapping("/algoOrder")
    public FuturesAlgoOrderResponse order(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @RequestParam(required = false) UUID algoId,
            @RequestParam(required = false) String clientAlgoId) {
        return futures.order(callerOf(caller), symbol, algoId, clientAlgoId);
    }

    @Operation(summary = "The waiting conditional orders, newest first. Binance: GET /fapi/v1/openAlgoOrders")
    @ApiResponse(responseCode = "200", description = "The orders")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/openAlgoOrders")
    public List<FuturesAlgoOrderResponse> openOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false)
                    String symbol) {
        return futures.openOrders(callerOf(caller), symbol);
    }

    @Operation(summary = "The latest conditional orders of a pair, oldest first. Binance: GET /fapi/v1/allAlgoOrders")
    @ApiResponse(responseCode = "200", description = "The orders")
    @ApiResponse(responseCode = "400", description = "MSG15: limit outside 1 … 1000, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the plan has no Futures feature")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/allAlgoOrders")
    public List<FuturesAlgoOrderResponse> allOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(description = "Placed at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Placed before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 500 when absent") @RequestParam(required = false) Integer limit) {
        return futures.allOrders(callerOf(caller), symbol, startTime, endTime, limit);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.request.FuturesAmendOrderRequest;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderWarningsResponse;
import com.cryptopilot.paper.dto.response.FuturesTradeResponse;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.FuturesOrderWarningService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order endpoints of paper Futures USDⓈ-M, named after the exchange's ({@code /fapi/v1/order} becomes
 * {@code /api/v1/paper/futures/order}), with the same parameter and field names. Differences, as for the settings
 * ({@link FuturesConfigController}): the caller is the Trader of the session; bodies are JSON with boolean flags; ids
 * are UUIDs and times ISO-8601; a refusal is the project's problem detail, carrying the exchange's code and message as
 * {@code binanceCode} and {@code binanceMsg}. Paper money only: nothing here reaches an exchange.
 *
 * <p>Rule: TR-15, TR-16, TR-25, TR-26; D-127; Q-T10.
 */
@Tag(
        name = "Demo trading: Futures orders",
        description = "LIMIT and MARKET orders of paper Futures, their risk warnings, their amendment and their trades"
                + " (TR-15, TR-16, TR-25, TR-26)."
                + " Trader with the Futures feature.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/futures")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesOrderController {

    private final FuturesOrderService futures;
    private final FuturesOrderWarningService warnings;

    @Operation(summary = "Place a LIMIT or MARKET order. Binance: POST /fapi/v1/order")
    @ApiResponse(
            responseCode = "200",
            description = "The order after placement: executed on arrival as far as the book allowed, waiting, or"
                    + " ended (EXPIRED with statusReason)")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or does not fit the type (a conditional type is an algo"
                    + " order, the exchange's -4120); PAPER_PRICE_FILTER, PAPER_LOT_SIZE, PAPER_MARKET_LOT_SIZE,"
                    + " PAPER_NOTIONAL: a filter of the pair; PAPER_POSITION_SIDE (-4061): not BOTH in one-way mode;"
                    + " PAPER_INSUFFICIENT_MARGIN (-2019)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_REDUCE_ONLY_REJECTED (-2022); PAPER_MAX_POSITION_AT_LEVERAGE (-2027);"
                    + " PAPER_ORDER_WOULD_TAKE (-5022, GTX); PAPER_MAX_NUM_ORDERS (-2025);"
                    + " PAPER_USER_IN_LIQUIDATION (-2023); PAIR_NOT_TRADING; DATA_CONFLICT: the key was used for"
                    + " another order")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE (MSG43): no current price to decide on")
    @PostMapping("/order")
    public FuturesOrderResponse place(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesOrderRequest request) {
        return futures.place(callerOf(caller), request);
    }

    @Operation(summary = "The risk warnings of an order before it is placed (the order form; no Binance endpoint)")
    @ApiResponse(
            responseCode = "200",
            description = "cost, budget, the estimated liquidationPrice and the warnings, most severe first:"
                    + " INSUFFICIENT_MARGIN, SL_BEYOND_LIQUIDATION (BLOCKING); HIGH_LEVERAGE, OVERSIZED_POSITION,"
                    + " LOW_RR, HIGH_FUNDING_RATE (WARNING); WIDE_STOP_LOSS (INFO). Nothing is placed")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing or does not fit the type; PAPER_POSITION_SIDE (-4061)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE (MSG43): no mark price")
    @PostMapping("/order/warnings")
    public FuturesOrderWarningsResponse warnings(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesOrderRequest request) {
        return warnings.warnings(callerOf(caller), request);
    }

    @Operation(summary = "Amend the price and quantity of a working LIMIT order. Binance: PUT /fapi/v1/order")
    @ApiResponse(
            responseCode = "200",
            description = "The order after the amendment: waiting at its new price, executed as far as the book"
                    + " allowed, or CANCELED when the new quantity is no more than what executed or a GTX order would"
                    + " execute")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: a field is missing, the order is not a LIMIT order or the side is not its own;"
                    + " PAPER_PRICE_FILTER, PAPER_LOT_SIZE, PAPER_NOTIONAL, PAPER_PERCENT_PRICE: a filter of the pair;"
                    + " PAPER_INSUFFICIENT_MARGIN (-2019). The order is left as it was")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the pair (the exchange's -2013)")
    @ApiResponse(
            responseCode = "409",
            description = "PAPER_ORDER_NOT_OPEN (-2011): the order has ended; PAPER_ORDER_UNCHANGED (-5027): same"
                    + " price and quantity; PAPER_MAX_AMENDMENTS (-5026): amended 10,000 times;"
                    + " PAPER_MAX_POSITION_AT_LEVERAGE (-2027); PAPER_REDUCE_ONLY_REJECTED (-2022);"
                    + " PAPER_USER_IN_LIQUIDATION (-2023)")
    @ApiResponse(responseCode = "503", description = "MARKET_PRICE_UNAVAILABLE (MSG43): the mark is stale")
    @PutMapping("/order")
    public FuturesOrderResponse amend(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody FuturesAmendOrderRequest request) {
        return futures.amend(callerOf(caller), request);
    }

    @Operation(summary = "Cancel a working order. Binance: DELETE /fapi/v1/order")
    @ApiResponse(responseCode = "200", description = "The order, CANCELED")
    @ApiResponse(responseCode = "400", description = "MSG01: neither orderId nor origClientOrderId")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the pair (the exchange's -2013)")
    @ApiResponse(responseCode = "409", description = "PAPER_ORDER_NOT_OPEN (-2011): the order has ended")
    @DeleteMapping("/order")
    public FuturesOrderResponse cancel(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String origClientOrderId) {
        return futures.cancel(callerOf(caller), symbol, orderId, origClientOrderId);
    }

    @Operation(summary = "Cancel every working order of a pair. Binance: DELETE /fapi/v1/allOpenOrders")
    @ApiResponse(responseCode = "200", description = "The orders cancelled; empty when there were none")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @DeleteMapping("/allOpenOrders")
    public List<FuturesOrderResponse> cancelAll(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol) {
        return futures.cancelAll(callerOf(caller), symbol);
    }

    @Operation(summary = "One order. Binance: GET /fapi/v1/order")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "400", description = "MSG01: neither orderId nor origClientOrderId")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the pair")
    @GetMapping("/order")
    public FuturesOrderResponse order(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String origClientOrderId) {
        return futures.order(callerOf(caller), symbol, orderId, origClientOrderId);
    }

    @Operation(summary = "The working orders, newest first. Binance: GET /fapi/v1/openOrders")
    @ApiResponse(responseCode = "200", description = "The orders")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/openOrders")
    public List<FuturesOrderResponse> openOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false)
                    String symbol) {
        return futures.openOrders(callerOf(caller), symbol);
    }

    @Operation(summary = "The latest orders of a pair, oldest first. Binance: GET /fapi/v1/allOrders")
    @ApiResponse(responseCode = "200", description = "The orders")
    @ApiResponse(responseCode = "400", description = "MSG15: limit outside 1 … 1000, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/allOrders")
    public List<FuturesOrderResponse> allOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(description = "Placed at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Placed before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 500 when absent") @RequestParam(required = false) Integer limit) {
        return futures.allOrders(callerOf(caller), symbol, startTime, endTime, limit);
    }

    @Operation(summary = "The Trader's liquidation orders, newest first. Binance: GET /fapi/v1/forceOrders")
    @ApiResponse(responseCode = "200", description = "The liquidation orders (clientOrderId autoclose-…)")
    @ApiResponse(responseCode = "400", description = "MSG15: limit outside 1 … 100, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/forceOrders")
    public List<FuturesOrderResponse> forceOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair; every pair when absent") @RequestParam(required = false) String symbol,
            @Parameter(description = "Placed at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Placed before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 100; 50 when absent") @RequestParam(required = false) Integer limit) {
        return futures.forceOrders(callerOf(caller), symbol, startTime, endTime, limit);
    }

    @Operation(summary = "The latest trades of a pair, oldest first. Binance: GET /fapi/v1/userTrades")
    @ApiResponse(
            responseCode = "200",
            description = "The trades; a fill that reversed the position is two, one per position")
    @ApiResponse(responseCode = "400", description = "MSG15: limit outside 1 … 1000, or startTime after endTime")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened or the symbol is unknown")
    @GetMapping("/userTrades")
    public List<FuturesTradeResponse> userTrades(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, e.g. BTCUSDT") @RequestParam String symbol,
            @Parameter(description = "Traded at or after, ISO-8601") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "Traded before, ISO-8601") @RequestParam(required = false) Instant endTime,
            @Parameter(description = "1 to 1000; 500 when absent") @RequestParam(required = false) Integer limit) {
        return futures.userTrades(callerOf(caller), symbol, startTime, endTime, limit);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

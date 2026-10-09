package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PlacedOrder;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.service.PaperOrderService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order endpoints of demo trading (TR-02), Trader only: place and cancel Spot MARKET and LIMIT orders, and read the
 * open orders, the order history and the trade history. Paper money only: nothing here reaches an exchange. The caller
 * is always the owner; every endpoint answers MSG41 until the account is opened.
 *
 * <p>Rule: TR-02.
 */
@Tag(name = "Demo trading: orders", description = "Place, cancel and list paper orders and fills (TR-02). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOrderController {

    private final PaperOrderService orders;

    @Operation(summary = "Place a Spot MARKET or LIMIT order; once per clientOrderId")
    @ApiResponse(responseCode = "201", description = "The order, placed now: FILLED, NEW (waiting) or EXPIRED")
    @ApiResponse(responseCode = "200", description = "The order placed earlier with the same clientOrderId")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: `errors` names each rejected field; PAPER_INSUFFICIENT_BALANCE (MSG01)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened, or the pair is not tradable")
    @ApiResponse(responseCode = "409", description = "MSG43: the clientOrderId was used for another order")
    @ApiResponse(responseCode = "503", description = "MSG43: no current price for an order that executes now")
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody PlaceOrderRequest request) {
        PlacedOrder placed = orders.place(callerOf(caller), request);
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(placed.order());
    }

    @Operation(summary = "Cancel a working order and release what it locked")
    @ApiResponse(responseCode = "200", description = "The order, CANCELED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the order has finished")
    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt caller, @PathVariable UUID orderId) {
        return orders.cancel(callerOf(caller), orderId);
    }

    @Operation(summary = "The caller's working orders, newest first")
    @ApiResponse(responseCode = "200", description = "The open orders")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/orders/open")
    public List<OrderResponse> openOrders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "One pair; every pair when absent") @RequestParam(required = false) UUID pairId) {
        return orders.openOrders(callerOf(caller), pairId);
    }

    @Operation(summary = "One order of the caller")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the caller")
    @GetMapping("/orders/{orderId}")
    public OrderResponse order(@AuthenticationPrincipal Jwt caller, @PathVariable UUID orderId) {
        return orders.order(callerOf(caller), orderId);
    }

    @Operation(summary = "The caller's order history, newest first, 20 per page by default")
    @ApiResponse(responseCode = "200", description = "One page of orders")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/orders")
    public PageResponse<OrderResponse> orders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "One pair; every pair when absent") @RequestParam(required = false) UUID pairId,
            @Parameter(description = "One status; every status when absent") @RequestParam(required = false)
                    OrderStatus status,
            @Parameter(description = "Placed at or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Placed before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return orders.orders(callerOf(caller), new OrderQuery(pairId, status, from, to, page, pageSize));
    }

    @Operation(summary = "The caller's trade history: every fill, newest first, 20 per page by default")
    @ApiResponse(responseCode = "200", description = "One page of fills")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/fills")
    public PageResponse<FillResponse> fills(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "One pair; every pair when absent") @RequestParam(required = false) UUID pairId,
            @Parameter(description = "Traded at or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Traded before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return orders.fills(callerOf(caller), new OrderQuery(pairId, null, from, to, page, pageSize));
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

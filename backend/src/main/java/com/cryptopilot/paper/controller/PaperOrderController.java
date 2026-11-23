package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PlacedOrder;
import com.cryptopilot.paper.model.PlacedOrderList;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.service.PaperOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    /** How many rows matched, when a CSV export holds fewer. */
    static final String TOTAL = "X-Total-Count";

    /** Set to {@code export} when a CSV export was cut at its limit. */
    static final String TRUNCATED = "X-Result-Truncated";

    private final PaperOrderService orders;

    @Operation(summary = "Place a Spot MARKET or LIMIT order; once per clientOrderId")
    @ApiResponse(
            responseCode = "201",
            description = "The order, placed now: FILLED, NEW or PARTIALLY_FILLED (waiting), or EXPIRED")
    @ApiResponse(responseCode = "200", description = "The order placed earlier with the same clientOrderId")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: `errors` names each rejected field; PAPER_INSUFFICIENT_BALANCE (MSG01); a"
                    + " filter failure (MSG15): PAPER_PRICE_FILTER, PAPER_LOT_SIZE, PAPER_MARKET_LOT_SIZE,"
                    + " PAPER_NOTIONAL, PAPER_PERCENT_PRICE, PAPER_TRAILING_DELTA, with `messageArgs` field, value,"
                    + " limit and MIN, MAX or STEP")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened, or the pair is not tradable")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the clientOrderId was used for another order; too many open orders or stops on"
                    + " the pair (PAPER_MAX_NUM_ORDERS, PAPER_MAX_NUM_ALGO_ORDERS)")
    @ApiResponse(responseCode = "503", description = "MSG43: no current price for an order that executes now")
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody PlaceOrderRequest request) {
        PlacedOrder placed = orders.place(callerOf(caller), request);
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(placed.order());
    }

    @Operation(summary = "Place a Spot OCO, OTO or OTOCO group of orders (TR-10); once per listClientOrderId")
    @ApiResponse(responseCode = "201", description = "The group, EXECUTING, with its orders")
    @ApiResponse(responseCode = "200", description = "The group placed earlier with the same listClientOrderId")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15: `errors` names each rejected part or field; PAPER_INSUFFICIENT_BALANCE"
                    + " (MSG01); a filter failure (MSG15): PAPER_PRICE_FILTER, PAPER_LOT_SIZE, PAPER_NOTIONAL,"
                    + " PAPER_PERCENT_PRICE, with `messageArgs` field, value, limit and MIN, MAX or STEP")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened, or the pair is not tradable")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: a working LIMIT_MAKER would take the book, a pending stop would trigger at the"
                    + " working price, too many open orders or stops on the pair (PAPER_MAX_NUM_ORDERS,"
                    + " PAPER_MAX_NUM_ALGO_ORDERS), or the listClientOrderId was used for another group")
    @ApiResponse(responseCode = "503", description = "MSG43: no current price")
    @PostMapping("/order-lists")
    public ResponseEntity<OrderListResponse> placeList(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody PlaceOrderListRequest request) {
        PlacedOrderList placed = orders.placeList(callerOf(caller), request);
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(placed.list());
    }

    @Operation(summary = "Cancel every order of a group that works or waits, releasing what they locked")
    @ApiResponse(responseCode = "200", description = "The group, ALL_DONE")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such group of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the group has finished")
    @PostMapping("/order-lists/{listId}/cancel")
    public OrderListResponse cancelList(@AuthenticationPrincipal Jwt caller, @PathVariable UUID listId) {
        return orders.cancelList(callerOf(caller), listId);
    }

    @Operation(summary = "One group of the caller's orders")
    @ApiResponse(responseCode = "200", description = "The group with its orders")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such group of the caller")
    @GetMapping("/order-lists/{listId}")
    public OrderListResponse orderList(@AuthenticationPrincipal Jwt caller, @PathVariable UUID listId) {
        return orders.orderList(callerOf(caller), listId);
    }

    @Operation(summary = "Cancel a working order and release what it locked; for an order of a group, the group")
    @ApiResponse(responseCode = "200", description = "The order, CANCELED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the order has finished")
    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt caller, @PathVariable UUID orderId) {
        return orders.cancel(callerOf(caller), orderId);
    }

    @Operation(summary = "Cancel every working order of the caller on one pair and release what they locked")
    @ApiResponse(responseCode = "200", description = "The orders cancelled, oldest first; empty when none was working")
    @ApiResponse(responseCode = "400", description = "MSG01: no pairId")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @DeleteMapping("/orders/open")
    public List<OrderResponse> cancelAll(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair, as Binance asks for the symbol") @RequestParam UUID pairId) {
        return orders.cancelAll(callerOf(caller), pairId);
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
            @Parameter(description = "One order type; every type when absent") @RequestParam(required = false)
                    OrderType type,
            @Parameter(description = "BUY or SELL; both when absent") @RequestParam(required = false) OrderSide side,
            @Parameter(description = "Placed at or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Placed before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return orders.orders(callerOf(caller), OrderQuery.orders(pairId, status, type, side, from, to, page, pageSize));
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
            @Parameter(description = "The fills of one order; every order when absent") @RequestParam(required = false)
                    UUID orderId,
            @Parameter(description = "BUY or SELL; both when absent") @RequestParam(required = false) OrderSide side,
            @Parameter(description = "Traded at or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Traded before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return orders.fills(callerOf(caller), OrderQuery.fills(pairId, orderId, side, from, to, page, pageSize));
    }

    @Operation(summary = "The same order history as a CSV file, at most 10,000 orders, newest first (B7)")
    @ApiResponse(
            responseCode = "200",
            description = "text/csv, a header line then one line per order. When more than 10,000 match:"
                    + " `X-Total-Count: <n>` and `X-Result-Truncated: export`.")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/orders/export")
    public ResponseEntity<String> exportOrders(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) UUID pairId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderType type,
            @RequestParam(required = false) OrderSide side,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return csv(
                "cryptopilot-spot-orders.csv",
                orders.exportOrders(
                        callerOf(caller), OrderQuery.orders(pairId, status, type, side, from, to, null, null)));
    }

    @Operation(summary = "The same trade history as a CSV file, at most 10,000 fills, newest first (B7)")
    @ApiResponse(
            responseCode = "200",
            description = "text/csv, a header line then one line per fill. When more than 10,000 match:"
                    + " `X-Total-Count: <n>` and `X-Result-Truncated: export`.")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened yet")
    @GetMapping("/fills/export")
    public ResponseEntity<String> exportFills(
            @AuthenticationPrincipal Jwt caller,
            @RequestParam(required = false) UUID pairId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) OrderSide side,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return csv(
                "cryptopilot-spot-trades.csv",
                orders.exportFills(callerOf(caller), OrderQuery.fills(pairId, orderId, side, from, to, null, null)));
    }

    private static ResponseEntity<String> csv(String name, PaperOrderService.Export export) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString());
        if (export.truncated()) {
            answer.header(TOTAL, String.valueOf(export.total())).header(TRUNCATED, "export");
        }
        return answer.body(export.csv());
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

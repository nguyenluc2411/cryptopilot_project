package com.cryptopilot.billing.controller;

import com.cryptopilot.billing.dto.AdminOrderDetailResponse;
import com.cryptopilot.billing.dto.AdminOrderResponse;
import com.cryptopilot.billing.dto.HandleOrderRequest;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.service.OrderAdminService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
 * The subscription orders in the administration console (SCR-40, UC-48). Admin only, by the {@code /api/v1/admin/**}
 * rule of the security chain.
 *
 * <p>Rule: UC-48; NSF-13; BR-57; D-146.
 */
@Tag(name = "Admin: payments", description = "Subscription orders, reconciliation with the gateway, flagged orders.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminOrderController {

    private final OrderAdminService service;

    @Operation(summary = "The subscription orders, newest first, filtered")
    @ApiResponse(responseCode = "200", description = "One page, 20 orders by default")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: period, page or pageSize")
    @GetMapping
    public PageResponse<AdminOrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderType orderType,
            @RequestParam(required = false) GatewayCode gateway,
            @Parameter(description = "First day of creation (Asia/Ho_Chi_Minh), e.g. 2026-11-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Last day of creation, included")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "true: only the orders waiting for, or handled by, an administrator")
                    @RequestParam(required = false)
                    Boolean manual,
            @Parameter(description = "The order code or its beginning") @RequestParam(required = false)
                    String orderCode,
            @Parameter(description = "Only this Trader's orders") @RequestParam(required = false) UUID userId,
            @Parameter(description = "From 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100, 20 by default") @RequestParam(required = false) Integer pageSize) {
        return service.list(
                new OrderAdminService.Criteria(status, orderType, gateway, from, to, manual, orderCode, userId),
                page,
                pageSize);
    }

    @Operation(summary = "One order with what its gateway answered and the subscription it opened")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order")
    @GetMapping("/{orderCode}")
    public AdminOrderDetailResponse detail(@PathVariable String orderCode) {
        return service.detail(orderCode);
    }

    @Operation(
            summary = "Ask the gateway about the order now (audited)",
            description = "A confirmed payment settles the order as its IPN would; an unpaid order past its payment"
                    + " window expires. PAID and FAILED orders are returned unchanged.")
    @ApiResponse(responseCode = "200", description = "The order afterwards")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order")
    @ApiResponse(
            responseCode = "503",
            description = "PAYMENT_GATEWAY_UNAVAILABLE, MSG43: the gateway could not be asked; try again later")
    @PostMapping("/{orderCode}/reconcile")
    public AdminOrderDetailResponse reconcile(@AuthenticationPrincipal Jwt caller, @PathVariable String orderCode) {
        return service.reconcile(UUID.fromString(caller.getSubject()), orderCode);
    }

    @Operation(summary = "Record how a flagged order was handled, e.g. refunded outside CryptoPilot (audited)")
    @ApiResponse(responseCode = "200", description = "The order, handled")
    @ApiResponse(responseCode = "400", description = "MSG15: the note is empty or longer than 1,000 characters")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order")
    @ApiResponse(
            responseCode = "409",
            description = "ORDER_NOT_AWAITING_HANDLING, MSG43: the order is not flagged or was handled already")
    @PostMapping("/{orderCode}/handled")
    public AdminOrderDetailResponse markHandled(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable String orderCode,
            @Valid @RequestBody HandleOrderRequest request) {
        return service.markHandled(UUID.fromString(caller.getSubject()), orderCode, request.note());
    }
}

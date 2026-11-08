package com.cryptopilot.billing.controller;

import com.cryptopilot.billing.dto.CreateOrderRequest;
import com.cryptopilot.billing.dto.CreateOrderResponse;
import com.cryptopilot.billing.dto.GatewayResponse;
import com.cryptopilot.billing.dto.OrderResponse;
import com.cryptopilot.billing.dto.PackageResponse;
import com.cryptopilot.billing.dto.QuoteRequest;
import com.cryptopilot.billing.dto.QuoteResponse;
import com.cryptopilot.billing.dto.SubscriptionResponse;
import com.cryptopilot.billing.service.BillingService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The subscription and payment endpoints of SCR-30…32 and the plan of SCR-07, Trader only. The caller is always the
 * owner: an order of another Trader reads as not found.
 *
 * <p>Rule: BR-53…BR-56, BR-63 as amended by D-146; SRS 3.10; TECHNICAL_DESIGN 7.11.
 */
@Tag(name = "Billing", description = "Packages, payment orders and the plan held (SCR-30…32, SCR-07). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BillingController {

    private final BillingService billing;

    @Operation(summary = "FREE and every paid package on sale, lowest tier first (SCR-30)")
    @ApiResponse(responseCode = "200", description = "The packages with the entitlements of their tier")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/packages")
    public List<PackageResponse> packages() {
        return billing.packages();
    }

    @Operation(summary = "The payment gateways and whether each takes new orders now (SCR-30, D-146)")
    @ApiResponse(responseCode = "200", description = "MOMO and SEPAY; `enabled` is false when switched off")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/gateways")
    public List<GatewayResponse> gateways() {
        return billing.gateways();
    }

    @Operation(summary = "What buying a package would do if paid now: type, amount, days carried over (SCR-30)")
    @ApiResponse(responseCode = "200", description = "The quote; it is worked out again when the order is paid")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @ApiResponse(
            responseCode = "409",
            description = "PACKAGE_NOT_ON_SALE; SUBSCRIPTION_DOWNGRADE_REFUSED: a lower tier than the plan held,"
                    + " messageArgs are the plan and its end")
    @PostMapping("/orders/quote")
    public QuoteResponse quote(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody QuoteRequest request) {
        return billing.quote(callerOf(caller), request);
    }

    @Operation(summary = "Create a PENDING order and get the way to the gateway's payment page (SCR-30)")
    @ApiResponse(
            responseCode = "201",
            description = "The order and the checkout: open `url` for a GET, submit `fields` to it for a POST")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such package")
    @ApiResponse(
            responseCode = "409",
            description = "PACKAGE_NOT_ON_SALE; SUBSCRIPTION_DOWNGRADE_REFUSED; PAYMENT_GATEWAY_DISABLED: the gateway"
                    + " is switched off or not available")
    @ApiResponse(
            responseCode = "503",
            description = "PAYMENT_GATEWAY_UNAVAILABLE: the gateway did not answer; no order was kept")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse createOrder(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateOrderRequest request) {
        return billing.createOrder(callerOf(caller), request);
    }

    @Operation(summary = "The caller's orders, newest first (SCR-32)")
    @ApiResponse(responseCode = "200", description = "One page of orders")
    @ApiResponse(responseCode = "400", description = "MSG15: page or pageSize out of range")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/orders")
    public PageResponse<OrderResponse> orders(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "From 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1–100; default 20") @RequestParam(required = false) Integer pageSize) {
        return billing.orders(callerOf(caller), page, pageSize);
    }

    @Operation(summary = "One of the caller's orders; SCR-31 polls it for the result")
    @ApiResponse(responseCode = "200", description = "The order; the result is its `status`")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such order of the caller")
    @GetMapping("/orders/{orderCode}")
    public OrderResponse order(@AuthenticationPrincipal Jwt caller, @PathVariable String orderCode) {
        return billing.order(callerOf(caller), orderCode);
    }

    @Operation(summary = "The plan the caller holds now (SCR-07, SCR-30)")
    @ApiResponse(responseCode = "200", description = "The tier; on a paid plan, the subscription that gives it")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping("/subscription")
    public SubscriptionResponse subscription(@AuthenticationPrincipal Jwt caller) {
        return billing.subscription(callerOf(caller));
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

package com.cryptopilot.trading.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.enums.PlanTab;
import com.cryptopilot.trading.service.TradingPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trading plan endpoints of SCR-16 to SCR-18 (UC-16 to UC-20), Trader only. The caller is the plan's owner in
 * every call; the service never loads another Trader's plan. Close position (UC-21) arrives with open positions
 * (T-042).
 *
 * <p>Rule: UC-16 to UC-20; SRS 3.1.3, 3.5.1, 3.5.2; CR-04; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade" over a "Service Layer".
 */
@Tag(name = "Trading plans", description = "Trading plans of SCR-16 to SCR-18 (UC-16 to UC-20). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingPlanController {

    private final TradingPlanService plans;

    @Operation(summary = "Calculate the risk panel of the values entered; nothing is saved (SRS 3.5.1)")
    @ApiResponse(responseCode = "200", description = "The calculation and its warnings (MSG17)")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15, MSG16: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not enabled on the market")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for a MARKET entry")
    @PostMapping("/calculate")
    public PlanCalculationResponse calculate(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateTradingPlanRequest request) {
        return plans.calculate(callerOf(caller), request);
    }

    @Operation(summary = "Create a plan as a draft, or save and activate it (UC-16)")
    @ApiResponse(responseCode = "201", description = "The plan; MSG19 when it was activated")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15, MSG16: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not enabled on the market")
    @ApiResponse(
            responseCode = "409",
            description = "MSG18: a BLOCKING warning; MSG27: the limit of ACTIVE plans and open positions")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for a MARKET entry")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradingPlanResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateTradingPlanRequest request) {
        return plans.create(callerOf(caller), request);
    }

    @Operation(summary = "The caller's plans, newest first, 20 per page by default (SCR-16, CR-04)")
    @ApiResponse(responseCode = "200", description = "One page of plans")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<TradingPlanSummaryResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "DRAFT, ACTIVE, EXECUTED or CANCELLED_EXPIRED; every status when absent")
                    @RequestParam(required = false)
                    PlanTab tab,
            @Parameter(description = "Only this pair") @RequestParam(required = false) UUID pairId,
            @Parameter(description = "SPOT or FUTURES") @RequestParam(required = false) MarketType market,
            @Parameter(description = "Created at or after, ISO 8601 UTC") @RequestParam(required = false) Instant from,
            @Parameter(description = "Created before, ISO 8601 UTC") @RequestParam(required = false) Instant to,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return plans.list(callerOf(caller), new PlanListQuery(tab, pairId, market, from, to, page, pageSize));
    }

    @Operation(summary = "A plan with its snapshot, warnings and status history (SCR-18)")
    @ApiResponse(responseCode = "200", description = "The plan")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @GetMapping("/{id}")
    public TradingPlanResponse get(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return plans.get(callerOf(caller), id);
    }

    @Operation(summary = "Edit a draft, or save and activate it (UC-17)")
    @ApiResponse(responseCode = "200", description = "The plan")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15, MSG16: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller, or the pair is not enabled")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is not a draft; MSG18: a BLOCKING warning; MSG27: the plan limit")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for a MARKET entry")
    @PutMapping("/{id}")
    public TradingPlanResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTradingPlanRequest request) {
        return plans.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Activate a draft (UC-18, BR-62)")
    @ApiResponse(responseCode = "200", description = "MSG19: the plan is ACTIVE")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15, MSG16: the plan no longer passes the calculation")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller, or the pair is not enabled")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is not a draft; MSG18: a BLOCKING warning; MSG27: the plan limit")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for a MARKET entry")
    @PostMapping("/{id}/activate")
    public TradingPlanResponse activate(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return plans.activate(callerOf(caller), id);
    }

    @Operation(summary = "Cancel a draft or active plan (UC-19)")
    @ApiResponse(responseCode = "200", description = "The plan, CANCELLED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is executed, cancelled or expired, or changed concurrently")
    @PostMapping("/{id}/cancel")
    public TradingPlanResponse cancel(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return plans.cancel(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

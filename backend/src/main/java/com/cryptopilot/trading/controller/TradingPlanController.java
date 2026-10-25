package com.cryptopilot.trading.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.dto.request.UpdateTradingPlanRequest;
import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.enums.PlanTab;
import com.cryptopilot.trading.service.PositionService;
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
 * every call; the service never loads another Trader's plan. The close of an open position (UC-21, SCR-19) is
 * estimated and executed here too.
 *
 * <p>Rule: UC-16 to UC-21; SRS 3.1.3, 3.5.1, 3.5.2; CR-04; TECHNICAL_DESIGN 8.
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

    /** The MSG51 answer of an activation, in the 400 description of each endpoint that activates. */
    private static final String MSG51 = "MSG51 (code ENTRY_DEVIATION_CONFIRMATION_REQUIRED): a MARKET entry moved from"
            + " ENTRY_DEVIATION_CONFIRM_R up to and including the plan's limit in R (BR-69); the body carries, as decimal"
            + " texts, deviationR, maxDeviationR, expectedFillPrice, positionQuantity, riskRewardRatio and netRiskReward,"
            + " and messageArgs [deviation, quantity, riskReward]. Repeat the request with confirmDeviation=true.";

    /** The MSG52 answer of an activation, in the 409 description of each endpoint that activates. */
    private static final String MSG52 = "MSG52 (code ENTRY_DEVIATION_REFUSED): a MARKET entry moved beyond the plan's"
            + " limit in R, or to or past the take profit (BR-69); no confirmation lifts it. The body carries deviationR,"
            + " maxDeviationR, expectedFillPrice, takeProfitReached and messageArgs [deviation, max].";

    private final TradingPlanService plans;
    private final PositionService positions;

    @Operation(summary = "Calculate the risk panel of the values entered; nothing is saved (SRS 3.5.1)")
    @ApiResponse(
            responseCode = "200",
            description = "The calculation and its warnings (MSG17). The snapshot's fee figures (estimated entry, stop"
                    + " loss and take profit fees, slippage, loss at stop loss, fee share of risk, risk/reward after"
                    + " fees) include the trading fees and slippage only; funding and the liquidation clearance fee are"
                    + " not included (D-116)")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15, MSG16: `errors` names each rejected field; MSG01 on `capital` when the"
                    + " caller's profile has no default capital, which every plan is sized on (D-121)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not enabled on the market")
    @ApiResponse(
            responseCode = "503",
            description = "MSG43: no current price for a MARKET entry or to check the invalidation price")
    @PostMapping("/calculate")
    public PlanCalculationResponse calculate(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateTradingPlanRequest request) {
        return plans.calculate(callerOf(caller), request);
    }

    @Operation(summary = "Create a plan as a draft, or save and activate it (UC-16)")
    @ApiResponse(responseCode = "201", description = "The plan; MSG19 when it was activated")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15, MSG16: `errors` names each rejected field; MSG01 on `capital` when the"
                    + " caller's profile has no default capital, which every plan is sized on (D-121). " + MSG51)
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not enabled on the market")
    @ApiResponse(
            responseCode = "409",
            description = "MSG18: a BLOCKING warning; MSG27: the limit of ACTIVE plans and open positions. " + MSG52)
    @ApiResponse(
            responseCode = "503",
            description = "MSG43: no current price for a MARKET entry or to check the invalidation or stop price")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradingPlanResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateTradingPlanRequest request) {
        return plans.create(callerOf(caller), request);
    }

    @Operation(summary = "The caller's plans, newest first, 20 per page by default (SCR-16, CR-04)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of plans; an EXECUTED row carries its position status, OPEN or CLOSED")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<TradingPlanSummaryResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(
                            description =
                                    "DRAFT, ACTIVE, EXECUTED or CANCELLED_EXPIRED (with INVALIDATED); every status when absent")
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

    @Operation(summary = "A plan with its snapshot, warnings, status history and, once executed, its position (SCR-18)")
    @ApiResponse(
            responseCode = "200",
            description = "The plan. The snapshot's fee figures exclude funding and the liquidation clearance fee, and"
                    + " are null on a plan saved before they existed (D-116). An EXECUTED plan carries its position:"
                    + " fill, quantity, entry time and fee, the Futures liquidation price and, while OPEN, the"
                    + " unrealized P/L at the Futures mark or Spot last price, without fees (UC-20, Q-55)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @GetMapping("/{id}")
    public TradingPlanResponse get(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return plans.get(callerOf(caller), id);
    }

    @Operation(summary = "Edit a draft, or save and activate it (UC-17)")
    @ApiResponse(responseCode = "200", description = "The plan")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01, MSG15, MSG16: `errors` names each rejected field; MSG01 on `capital` when the"
                    + " caller's profile has no default capital, which every plan is sized on (D-121). " + MSG51)
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller, or the pair is not enabled")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is not a draft; MSG18: a BLOCKING warning; MSG27: the plan limit. " + MSG52)
    @ApiResponse(
            responseCode = "503",
            description = "MSG43: no current price for a MARKET entry or to check the invalidation or stop price")
    @PutMapping("/{id}")
    public TradingPlanResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTradingPlanRequest request) {
        return plans.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Activate a draft (UC-18, BR-62)")
    @ApiResponse(responseCode = "200", description = "MSG19: the plan is ACTIVE")
    @ApiResponse(
            responseCode = "400",
            description =
                    "MSG01, MSG15, MSG16: the plan no longer passes the calculation, its invalidation price is no longer on the far side of the current price, its STOP is already triggered (entryPrice), or the last price has already reached its stop loss (STOP) or take profit (LIMIT); MSG01 on `capital` when the profile has no default capital, the capital the plan is recalculated on (D-121). "
                            + MSG51)
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: Futures plans are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller, or the pair is not enabled")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is not a draft; MSG18: a BLOCKING warning; MSG27: the plan limit. " + MSG52)
    @ApiResponse(
            responseCode = "503",
            description = "MSG43: no current price for a MARKET entry or to check the invalidation or stop price")
    @PostMapping("/{id}/activate")
    public TradingPlanResponse activate(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Parameter(description = "true once the Trader confirmed the MSG51 of a MARKET plan; default false")
                    @RequestParam(defaultValue = "false")
                    boolean confirmDeviation) {
        return plans.activate(callerOf(caller), id, confirmDeviation);
    }

    @Operation(summary = "Cancel a draft or active plan (UC-19)")
    @ApiResponse(responseCode = "200", description = "The plan, CANCELLED")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is executed, cancelled, expired or invalidated, or changed concurrently")
    @PostMapping("/{id}/cancel")
    public TradingPlanResponse cancel(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return plans.cancel(callerOf(caller), id);
    }

    @Operation(summary = "Estimate closing the plan's open position now (UC-21, SCR-19, MSG21); nothing is stored")
    @ApiResponse(
            responseCode = "200",
            description = "The current last price (Futures: the last price, never the mark price), the exit price with"
                    + " SLIPPAGE_BPS, the price result, the entry fee, the taker exit fee, the funding so far (null on"
                    + " Spot, provisional while a settlement is missing) and the estimated net P/L")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the plan has no OPEN position")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for the pair")
    @GetMapping("/{id}/close-estimate")
    public CloseEstimateResponse closeEstimate(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return positions.closeEstimate(callerOf(caller), id);
    }

    @Operation(summary = "Close the plan's open position at the current last price (UC-21, BR-39)")
    @ApiResponse(
            responseCode = "200",
            description = "The plan, its position CLOSED by MANUAL: a taker exit at the current last price moved by"
                    + " SLIPPAGE_BPS, the taker fee, the funding up to now and the net P/L; MSG23 is sent")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such plan of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan has no OPEN position, e.g. the engine closed it first")
    @ApiResponse(responseCode = "503", description = "MSG43: no current last price for the pair")
    @PostMapping("/{id}/close-position")
    public TradingPlanResponse closePosition(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return positions.closePosition(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.response.AppliedPlanResponse;
import com.cryptopilot.paper.service.PaperPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apply plan (RF-33): the plan's orders are placed on the demo account, and the plan is EXECUTED when its entry fills.
 *
 * <p>Rule: BR-33, BR-71; D-117; RF-33.
 */
@Tag(name = "Demo trading: plans", description = "Carry out a trading plan with paper orders (RF-33). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper/plans")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPlanController {

    private final PaperPlanService plans;

    @Operation(summary = "Apply an ACTIVE plan: place its entry order with its take profit and stop loss")
    @ApiResponse(responseCode = "201", description = "The orders placed; the plan is EXECUTED once the entry fills")
    @ApiResponse(
            responseCode = "400",
            description = "PAPER_INSUFFICIENT_BALANCE (MSG01), or a filter failure of the order paths (MSG15)")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no such plan, the demo account is not opened, or the pair is not tradable")
    @ApiResponse(
            responseCode = "409",
            description = "MSG43: the plan is not ACTIVE (TRADING_PLAN_STATUS_TRANSITION_INVALID), or an order of it"
                    + " still works (PLAN_ALREADY_APPLIED)")
    @ApiResponse(responseCode = "503", description = "MSG43: no current price, or trading is paused")
    @PostMapping("/{planId}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public AppliedPlanResponse apply(@AuthenticationPrincipal Jwt caller, @PathVariable UUID planId) {
        return plans.apply(UUID.fromString(caller.getSubject()), planId);
    }
}

package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.response.EquityResponse;
import com.cryptopilot.paper.dto.response.SpotPnlResponse;
import com.cryptopilot.paper.service.PaperPnlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cost and worth of the caller's demo holdings (TR-13). Orchestration only.
 *
 * <p>Rule: TR-13.
 */
@Tag(name = "Demo trading: PnL", description = "Spot average cost and PnL, account worth by day (TR-13). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/paper")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPnlController {

    private final PaperPnlService pnl;

    @Operation(summary = "Each Spot coin's average cost, value and unrealised PnL")
    @ApiResponse(responseCode = "200", description = "The coins held, largest value first")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened")
    @GetMapping("/spot/pnl")
    public SpotPnlResponse spotPnl(@AuthenticationPrincipal Jwt caller) {
        return pnl.spotPnl(callerOf(caller));
    }

    @Operation(summary = "The account's worth now, today's PnL, and its worth at the start of each day")
    @ApiResponse(responseCode = "200", description = "The worth and its daily history, oldest first")
    @ApiResponse(responseCode = "400", description = "MSG01: days outside 1 to 365")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the account is not opened")
    @GetMapping("/equity")
    public EquityResponse equity(@AuthenticationPrincipal Jwt caller, @RequestParam(required = false) Integer days) {
        return pnl.equity(callerOf(caller), days);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

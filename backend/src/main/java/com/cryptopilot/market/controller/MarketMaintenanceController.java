package com.cryptopilot.market.controller;

import com.cryptopilot.market.dto.request.MaintenanceRequest;
import com.cryptopilot.market.dto.request.ResumeRequest;
import com.cryptopilot.market.dto.response.MarketIncidentResponse;
import com.cryptopilot.market.dto.response.MarketStatusResponse;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration console's maintenance switch of each market and its incidents (D-159): an administrator closes
 * trading on a market on purpose, before a known exchange maintenance or a deployment, and ends it; trading reopens
 * through recovery, never while the market's data is lost. A recovery that cannot end by itself, after an outage
 * longer than can be replayed, is ended here by hand.
 *
 * <p>Rule: NSF-03; BR-57; D-159.
 */
@Tag(name = "Admin: market health", description = "Maintenance switch and incidents of each market (D-159).")
@RestController
@RequestMapping("/api/v1/admin/market")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketMaintenanceController {

    private final MarketStatusService service;

    @Operation(summary = "Start or end maintenance of a market (audited)")
    @ApiResponse(responseCode = "200", description = "Every market's status after the change")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @PostMapping("/maintenance")
    public MarketStatusResponse maintenance(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody MaintenanceRequest request) {
        return service.maintenance(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "Reopen a recovering market by hand (audited), for an outage longer than can be replayed")
    @ApiResponse(responseCode = "200", description = "Every market's status after the change")
    @ApiResponse(responseCode = "400", description = "MSG01: `errors` names each rejected field")
    @ApiResponse(responseCode = "409", description = "DATA_CONFLICT: the market is not recovering")
    @PostMapping("/resume")
    public MarketStatusResponse resume(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody ResumeRequest request) {
        return service.resume(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "The latest incidents of a market, newest first")
    @ApiResponse(responseCode = "200", description = "At most `limit` incidents")
    @ApiResponse(responseCode = "400", description = "MSG01: a limit outside 1 to 100")
    @GetMapping("/incidents")
    public List<MarketIncidentResponse> incidents(
            @Parameter(description = "SPOT or FUTURES") @RequestParam MarketType market,
            @Parameter(description = "1 to 100") @RequestParam(defaultValue = "20") int limit) {
        return service.incidents(market, limit);
    }
}

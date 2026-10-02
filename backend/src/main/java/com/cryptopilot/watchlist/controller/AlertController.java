package com.cryptopilot.watchlist.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.dto.request.UpdateAlertRequest;
import com.cryptopilot.watchlist.dto.response.AlertResponse;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The alert endpoints of SCR-13 and SCR-14 (UC-13, UC-14), Trader only. The caller is always the owner: the service
 * never loads another Trader's alert.
 *
 * <p>Rule: UC-13, UC-14; BR-16, BR-17; SRS 3.4.2, 3.4.3; TECHNICAL_DESIGN 8.
 *
 * <p>Reference: IETF RFC 9110 (2022). <i>HTTP Semantics</i>, §9.3 (methods) and §15 (status codes).
 */
@Tag(name = "Alerts", description = "The alerts of SCR-13 and SCR-14 (UC-13, UC-14). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AlertController {

    private final AlertService alerts;

    @Operation(summary = "The caller's alerts, newest first, 20 per page by default (SCR-13)")
    @ApiResponse(responseCode = "200", description = "One page of alerts")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a parameter is invalid")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public PageResponse<AlertResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "ACTIVE, PAUSED, TRIGGERED or EXPIRED; every status when absent")
                    @RequestParam(required = false)
                    AlertStatus status,
            @Parameter(description = "PRICE or INDICATOR; both when absent") @RequestParam(required = false)
                    AlertType type,
            @Parameter(description = "Page from 1; default 1") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 to 100; default 20") @RequestParam(required = false) Integer pageSize) {
        return alerts.list(callerOf(caller), status, type, page, pageSize);
    }

    @Operation(summary = "Create an alert; a pair not yet watched is added to the watchlist (UC-13, BR-16)")
    @ApiResponse(responseCode = "201", description = "The ACTIVE alert; the client shows MSG14")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(
            responseCode = "403",
            description = "MSG29: indicator alerts, Futures alerts or email and push are not in the caller's plan")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is not enabled on the market")
    @ApiResponse(responseCode = "409", description = "MSG27: the ACTIVE alert limit or the watchlist limit")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertResponse create(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateAlertRequest request) {
        return alerts.create(callerOf(caller), request);
    }

    @Operation(summary = "Replace an alert's rule; a TRIGGERED or EXPIRED alert becomes ACTIVE again (SRS 3.4.3)")
    @ApiResponse(responseCode = "200", description = "The alert; the client shows MSG14")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: a feature of the rule is not in the caller's plan")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no such alert of the caller, or the pair is not enabled on the market")
    @ApiResponse(responseCode = "409", description = "MSG27: re-activating it would pass the ACTIVE alert limit")
    @PutMapping("/{id}")
    public AlertResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAlertRequest request) {
        return alerts.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Remove an alert (SRS 3.4.3)")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such alert of the caller")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        alerts.delete(callerOf(caller), id);
    }

    @Operation(summary = "Pause an ACTIVE alert (SRS 3.4.3)")
    @ApiResponse(responseCode = "200", description = "The PAUSED alert")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such alert of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the alert is not ACTIVE")
    @PostMapping("/{id}/pause")
    public AlertResponse pause(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return alerts.pause(callerOf(caller), id);
    }

    @Operation(summary = "Resume a PAUSED alert within the plan's limits (SRS 3.4.3)")
    @ApiResponse(responseCode = "200", description = "The ACTIVE alert")
    @ApiResponse(responseCode = "400", description = "MSG15: its expiry has passed; edit it instead")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "403", description = "MSG29: the alert's type or market is not in the caller's plan")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: no such alert of the caller, or the pair is not enabled on the market")
    @ApiResponse(responseCode = "409", description = "MSG27: the ACTIVE alert limit; MSG43: the alert is not PAUSED")
    @PostMapping("/{id}/resume")
    public AlertResponse resume(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return alerts.resume(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

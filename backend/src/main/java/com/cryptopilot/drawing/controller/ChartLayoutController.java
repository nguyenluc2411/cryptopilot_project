package com.cryptopilot.drawing.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.drawing.dto.request.CreateChartLayoutRequest;
import com.cryptopilot.drawing.dto.request.RenameChartLayoutRequest;
import com.cryptopilot.drawing.dto.response.ChartLayoutResponse;
import com.cryptopilot.drawing.service.ChartLayoutService;
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
 * The layouts a Trader keeps on the chart of each pair, Trader only: named sets of drawings the chart switches between.
 * The caller is always the owner: the service never loads another Trader's layout.
 *
 * <p>Rule: D-149; T-119.
 *
 * <p>Reference: IETF RFC 9110 (2022). <i>HTTP Semantics</i>, §9.3 (methods), §15 (status codes) and §15.5.10 "409
 * Conflict".
 */
@Tag(name = "Chart layouts", description = "The Trader's layouts of drawings on the chart of a pair (D-149). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/chart-layouts")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ChartLayoutController {

    private final ChartLayoutService layouts;

    @Operation(summary = "The caller's layouts on a pair, by number, with how many drawings each holds")
    @ApiResponse(responseCode = "200", description = "Every layout; empty when the caller never drew on the pair")
    @ApiResponse(responseCode = "400", description = "MSG01: `pairId` is missing or not a UUID")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public List<ChartLayoutResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair whose chart is shown") @RequestParam UUID pairId) {
        return layouts.list(callerOf(caller), pairId);
    }

    @Operation(summary = "Open a new layout on a pair, empty or a copy of another layout's drawings")
    @ApiResponse(responseCode = "201", description = "The new layout, under the smallest number still free")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01: `errors` names each rejected field, `copyFrom` when that layout is on another pair")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(
            responseCode = "404",
            description = "MSG41: the pair is unknown or not listed, or `copyFrom` is not a layout of the caller")
    @ApiResponse(responseCode = "409", description = "MSG43: the caller already keeps the most layouts on the pair")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChartLayoutResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateChartLayoutRequest request) {
        return layouts.create(callerOf(caller), request);
    }

    @Operation(summary = "Rename a layout; a blank name brings back its number")
    @ApiResponse(responseCode = "200", description = "The renamed layout")
    @ApiResponse(responseCode = "400", description = "MSG01: the name is too long")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such layout of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "The layout changed or was removed by another request; nothing written")
    @PutMapping("/{id}")
    public ChartLayoutResponse rename(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody RenameChartLayoutRequest request) {
        return layouts.rename(callerOf(caller), id, request);
    }

    @Operation(summary = "Remove a layout with its drawings (the client confirms first)")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such layout of the caller")
    @ApiResponse(
            responseCode = "409",
            description = "The layout changed or was removed by another request; nothing written")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        layouts.delete(callerOf(caller), id);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

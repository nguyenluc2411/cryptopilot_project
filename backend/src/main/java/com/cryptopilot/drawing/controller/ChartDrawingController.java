package com.cryptopilot.drawing.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.UpdateChartDrawingRequest;
import com.cryptopilot.drawing.dto.response.ChartDrawingResponse;
import com.cryptopilot.drawing.service.ChartDrawingService;
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
 * The drawings a Trader keeps on the chart of each pair, Trader only. The caller is always the owner: the service
 * never loads another Trader's drawing. The chart saves each change as it happens; there is no save button.
 *
 * <p>Rule: D-149; T-119.
 *
 * <p>Reference: IETF RFC 9110 (2022). <i>HTTP Semantics</i>, §9.3 (methods) and §15 (status codes).
 */
@Tag(name = "Chart drawings", description = "The Trader's drawings on the chart of a pair (D-149). Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/chart-drawings")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ChartDrawingController {

    private final ChartDrawingService drawings;

    @Operation(summary = "The caller's drawings on a pair, oldest first")
    @ApiResponse(responseCode = "200", description = "Every drawing; empty when the caller never drew on the pair")
    @ApiResponse(responseCode = "400", description = "MSG01: `pairId` is missing or not a UUID")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @GetMapping
    public List<ChartDrawingResponse> list(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair whose chart is shown") @RequestParam UUID pairId) {
        return drawings.list(callerOf(caller), pairId);
    }

    @Operation(summary = "Add a drawing to a pair's chart")
    @ApiResponse(responseCode = "201", description = "The new drawing")
    @ApiResponse(
            responseCode = "400",
            description = "MSG01/MSG15: `errors` names each rejected field, including anchors that do not fit the type")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: the pair is unknown or not listed on any market")
    @ApiResponse(responseCode = "409", description = "MSG43: the pair already holds the most drawings allowed")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChartDrawingResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CreateChartDrawingRequest request) {
        return drawings.create(callerOf(caller), request);
    }

    @Operation(summary = "Replace the anchors, style, text and flags of a drawing")
    @ApiResponse(responseCode = "200", description = "The drawing with its new version")
    @ApiResponse(responseCode = "400", description = "MSG01/MSG15: `errors` names each rejected field")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such drawing of the caller")
    @ApiResponse(responseCode = "409", description = "The drawing changed since `version` was read; nothing written")
    @PutMapping("/{id}")
    public ChartDrawingResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChartDrawingRequest request) {
        return drawings.update(callerOf(caller), id, request);
    }

    @Operation(summary = "Remove a drawing")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @ApiResponse(responseCode = "404", description = "MSG41: no such drawing of the caller")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        drawings.delete(callerOf(caller), id);
    }

    @Operation(summary = "Remove every drawing of the caller on a pair (the client confirms first)")
    @ApiResponse(responseCode = "204", description = "Removed, or there was nothing to remove")
    @ApiResponse(responseCode = "400", description = "MSG01: `pairId` is missing or not a UUID")
    @ApiResponse(responseCode = "401", description = "MSG44: no valid session")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(
            @AuthenticationPrincipal Jwt caller,
            @Parameter(description = "The pair whose drawings are removed") @RequestParam UUID pairId) {
        drawings.deleteAll(callerOf(caller), pairId);
    }

    private static UUID callerOf(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}

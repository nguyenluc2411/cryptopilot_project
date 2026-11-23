package com.cryptopilot.market.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.market.dto.request.LatencySamplesRequest;
import com.cryptopilot.market.dto.response.LatencyReportResponse;
import com.cryptopilot.market.service.LatencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The realtime latency (TR-36): the Trader's client reports what it measured, under {@code /api/v1/analysis/**}
 * (Trader), and the administrator reads the report, under {@code /api/v1/admin/**} (Admin).
 *
 * <p>Rule: TR-36; TR-34.
 */
@Tag(name = "Realtime latency", description = "Client latency samples and the latency report (TR-36).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LatencyController {

    private final LatencyService latency;

    @Operation(summary = "Report what the client measured from the exchange to its screen")
    @ApiResponse(responseCode = "204", description = "Recorded")
    @ApiResponse(responseCode = "400", description = "MSG01: no or too many samples, an unknown kind; MSG15: millis")
    @PostMapping("/api/v1/analysis/latency-samples")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void samples(@Valid @RequestBody LatencySamplesRequest request) {
        latency.record(request);
    }

    @Operation(summary = "The realtime latency percentiles and the market topics' subscriptions (Admin)")
    @ApiResponse(responseCode = "200", description = "The report of this instance since it started")
    @GetMapping("/api/v1/admin/latency")
    public LatencyReportResponse report() {
        return latency.report();
    }
}

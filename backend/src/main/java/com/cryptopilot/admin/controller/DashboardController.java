package com.cryptopilot.admin.controller;

import com.cryptopilot.admin.dto.response.DashboardResponse;
import com.cryptopilot.admin.dto.response.ReportResponse;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import com.cryptopilot.admin.service.DashboardService;
import com.cryptopilot.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-33 of the administration console: the dashboard and the management reports, with a CSV export (UC-40, UC-41).
 * Admin only, by the {@code /api/v1/admin/**} rule of the security chain. Dates are business days, UTC+7.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1.
 */
@Tag(name = "Admin: dashboard", description = "Indicators and management reports, SCR-33 (UC-40, UC-41).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DashboardController {

    /** {@code text/csv} as RFC 4180 registers it, in UTF-8. */
    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final DashboardService service;

    @Operation(summary = "The dashboard's indicators (UC-40); the last 30 days without a period")
    @ApiResponse(responseCode = "200", description = "Indicators by name")
    @ApiResponse(responseCode = "400", description = "MSG15: from is after to")
    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @Parameter(description = "First day, e.g. 2026-10-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "Last day, included")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return service.dashboard(from, to);
    }

    @Operation(summary = "A management report grouped by DAY, WEEK or MONTH (UC-41)")
    @ApiResponse(responseCode = "200", description = "One row per group, every column")
    @ApiResponse(responseCode = "400", description = "MSG15: from is after to, or more than 366 rows")
    @GetMapping("/reports")
    public ReportResponse report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "DAY (default), WEEK or MONTH") @RequestParam(required = false)
                    ReportGrouping groupBy) {
        return service.report(from, to, groupBy);
    }

    @Operation(summary = "The same report as a CSV file (UC-41)")
    @ApiResponse(responseCode = "200", description = "text/csv, a header line then one line per group")
    @ApiResponse(responseCode = "400", description = "MSG15: from is after to, or more than 366 rows")
    @GetMapping("/reports/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ReportGrouping groupBy) {
        ReportResponse report = service.report(from, to, groupBy);
        String name = "cryptopilot-report-" + report.from() + "-" + report.to() + "-"
                + report.groupBy().name().toLowerCase() + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(service.csv(report));
    }
}

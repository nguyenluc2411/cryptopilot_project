package com.cryptopilot.admin.controller;

import com.cryptopilot.admin.dto.response.SystemEventResponse;
import com.cryptopilot.admin.dto.response.SystemHealthResponse;
import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.SystemMonitorService;
import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The system log and the health panel of SCR-44 (UC-52): unexpected errors, failed jobs and failed calls to external
 * services, and the status of those services and of the market data feeds. Admin only.
 *
 * <p>Rule: UC-52; registration form, Admin System ("system errors, and external API failures").
 */
@Tag(name = "Admin: system log", description = "System errors, external API failures and health, SCR-44 (UC-52).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemMonitorController {

    private final SystemMonitorService service;

    @Operation(summary = "One page of the system log, the latest seen first")
    @ApiResponse(responseCode = "200", description = "20 rows by default; a repeated failure is one row with a count")
    @ApiResponse(responseCode = "400", description = "MSG01: an unknown filter value; MSG15: period, page or pageSize")
    @GetMapping("/system-events")
    public PageResponse<SystemEventResponse> events(
            @Parameter(description = "SYSTEM_ERROR or EXTERNAL_API_FAILURE") @RequestParam(required = false)
                    SystemEventCategory category,
            @RequestParam(required = false) SystemEventSource source,
            @Parameter(description = "First day the failure was last seen on")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return service.events(category, source, from, to, page, pageSize);
    }

    @Operation(summary = "The status of each external service and of each market's data feed")
    @ApiResponse(responseCode = "200", description = "Read now")
    @GetMapping("/system-health")
    public SystemHealthResponse health() {
        return service.health();
    }
}

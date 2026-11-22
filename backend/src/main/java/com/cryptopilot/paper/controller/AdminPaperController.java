package com.cryptopilot.paper.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.paper.dto.response.AdminPaperOverviewResponse;
import com.cryptopilot.paper.service.PaperMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo exchange's overview in the administration console (TR-30). Admin only, by the {@code /api/v1/admin/**} rule
 * of the security chain. Read only: the demo exchange's settings are system settings (SCR-43), audited there.
 *
 * <p>Rule: TR-30; plan section 9.
 */
@Tag(name = "Admin: demo trading", description = "The demo exchange at a glance (TR-30).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/paper")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminPaperController {

    private final PaperMonitorService monitor;

    @Operation(summary = "Accounts, working orders, open positions, the last 24 hours and the latest liquidations")
    @ApiResponse(responseCode = "200", description = "The overview now")
    @GetMapping("/overview")
    public AdminPaperOverviewResponse overview() {
        return monitor.overview();
    }
}

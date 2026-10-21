package com.cryptopilot.admin.controller;

import com.cryptopilot.admin.dto.request.UpdateSettingRequest;
import com.cryptopilot.admin.dto.response.SystemSettingResponse;
import com.cryptopilot.admin.service.SystemSettingService;
import com.cryptopilot.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCR-43 of the administration console: the system settings (UC-51). Admin only, by the {@code /api/v1/admin/**}
 * rule of the security chain.
 *
 * <p>Rule: SRS 3.11.8, UC-51; BR-57.
 */
@Tag(name = "Admin: settings", description = "Fees, warning thresholds and limits, SCR-43 (UC-51).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemSettingController {

    private final SystemSettingService service;

    @Operation(summary = "Every system setting, by key, with its allowed range")
    @ApiResponse(responseCode = "200", description = "The settings")
    @GetMapping
    public List<SystemSettingResponse> list() {
        return service.list();
    }

    @Operation(summary = "One system setting")
    @ApiResponse(responseCode = "200", description = "The setting")
    @ApiResponse(responseCode = "404", description = "MSG41: no such setting")
    @GetMapping("/{key}")
    public SystemSettingResponse get(@PathVariable String key) {
        return service.get(key);
    }

    @Operation(summary = "Change a setting; new operations use it at once (UC-51)")
    @ApiResponse(responseCode = "200", description = "The setting with its new value")
    @ApiResponse(responseCode = "400", description = "MSG01 or MSG15: `errors.value`, not of the type or out of range")
    @ApiResponse(responseCode = "404", description = "MSG41: no such setting")
    @PutMapping("/{key}")
    public SystemSettingResponse update(
            @AuthenticationPrincipal Jwt caller,
            @PathVariable String key,
            @Valid @RequestBody UpdateSettingRequest request) {
        return service.update(UUID.fromString(caller.getSubject()), key, request.value());
    }
}

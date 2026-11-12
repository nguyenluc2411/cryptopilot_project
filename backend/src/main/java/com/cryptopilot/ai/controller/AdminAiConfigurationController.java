package com.cryptopilot.ai.controller;

import com.cryptopilot.ai.dto.request.AiConfigurationRequest;
import com.cryptopilot.ai.dto.response.AiConfigurationResponse;
import com.cryptopilot.ai.service.AiConfigurationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI configuration versions of SCR-42 (UC-50), Admin only.
 *
 * <p>Rule: UC-50; BR-57; SRS 3.11.7.
 */
@Tag(name = "Admin: AI configuration", description = "Versions of the AI Assistant configuration, SCR-42 (UC-50).")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/admin/ai-configurations")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminAiConfigurationController {

    private final AiConfigurationService service;

    @Operation(summary = "Every version, the newest first; one of them is active (SCR-42)")
    @ApiResponse(responseCode = "200", description = "The versions")
    @GetMapping
    public List<AiConfigurationResponse> list() {
        return service.list();
    }

    @Operation(summary = "Add a version, saved inactive (UC-50); audited")
    @ApiResponse(responseCode = "201", description = "The new version")
    @ApiResponse(responseCode = "400", description = "MSG01, MSG15: a field is missing or out of range")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiConfigurationResponse create(
            @AuthenticationPrincipal Jwt caller, @Valid @RequestBody AiConfigurationRequest request) {
        return service.create(UUID.fromString(caller.getSubject()), request);
    }

    @Operation(summary = "Make a version the active one; the previous one is deactivated (UC-50); audited")
    @ApiResponse(responseCode = "200", description = "The version, now active")
    @ApiResponse(responseCode = "404", description = "MSG41: no such version")
    @PostMapping("/{id}/activate")
    public AiConfigurationResponse activate(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return service.activate(UUID.fromString(caller.getSubject()), id);
    }
}
